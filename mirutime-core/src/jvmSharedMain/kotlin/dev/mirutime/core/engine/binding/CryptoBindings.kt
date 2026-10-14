package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.Varargs
import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Crypto/codec bindings for Lua (port of NoveLA LuaHashFunctions /
// LuaCipherFunctions / LuaCodecFunctions with the controller rulings).
//
// Contract: hash/cipher outputs are RAW bytes (binary-str), not hex —
// callers hex-encode when needed (NoveLA precedent). Arguments are read
// via checkstring().m_bytes/m_offset/m_length — never checkjstring(),
// which would corrupt binary through a UTF-8 round-trip.
// *_decrypt errors return nil (Lua does `or original`); wrong argument
// TYPE throws LuaError (checkstring before try).
object CryptoBindings {

    fun install(session: LuaSession) {
        val globals = session.globals

        // ── hashes: raw bytes out ───────────────────────────────────────────
        globals.set("sha256", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val s = arg.checkstring()
                return LuaValue.valueOf(digest("SHA-256", s))
            }
        })
        globals.set("md5", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val s = arg.checkstring()
                return LuaValue.valueOf(digest("MD5", s))
            }
        })
        // NoveLA LuaHashFunctions.kt:59-70: sha1(data [, "hex"|"raw"]) — no
        // mode behaves like sha256 (raw bytes); "hex" → 40-char hex; anything
        // else → LuaError.
        globals.set("sha1", object : TwoArgFunction() {
            override fun call(dataArg: LuaValue, modeArg: LuaValue): LuaValue {
                val s = dataArg.checkstring()
                val out = digest("SHA-1", s)
                val mode = if (modeArg.isnil()) "raw" else modeArg.checkjstring()
                return when (mode) {
                    "raw" -> LuaValue.valueOf(out)
                    "hex" -> LuaValue.valueOf(out.toHex())
                    else -> throw LuaError("sha1: unknown mode \"$mode\" (hex|raw)")
                }
            }
        })
        // NoveLA LuaHashFunctions.kt:71-79: crc32(data) → unsigned CRC-32 as a
        // Lua number (fits a double exactly).
        globals.set("crc32", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val s = arg.checkstring()
                val crc = CRC32()
                crc.update(s.m_bytes, s.m_offset, s.m_length)
                return LuaValue.valueOf(crc.value.toDouble())
            }
        })
        globals.set("hmac_sha256", object : TwoArgFunction() {
            override fun call(keyArg: LuaValue, dataArg: LuaValue): LuaValue {
                val key = keyArg.checkstring()
                val data = dataArg.checkstring()
                val mac = try {
                    Mac.getInstance("HmacSHA256").apply {
                        init(SecretKeySpec(key.bytes(), "HmacSHA256"))
                    }
                } catch (e: IllegalArgumentException) {
                    // Empty key: SecretKeySpec rejects it — call error.
                    throw LuaError("hmac_sha256: ${e.message}")
                }
                mac.update(data.bytes())
                return LuaValue.valueOf(mac.doFinal())
            }
        })

        // ── ciphers ─────────────────────────────────────────────────────────
        // NoveLA LuaCipherFunctions.kt:54-75: 4-arg (key, iv, ct, tag) — raw
        // bytes (binary-str), the tag travels separately (javax.crypto
        // appends it after the ciphertext in doFinal). Unpacking bundled
        // forms (iv‖tag‖ct, "iv:tag:ct") is the caller's Lua-side job.
        // Wrong key/tag → nil (NoveLA contract).
        globals.set("aes_gcm_decrypt", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val key = args.arg(1).checkstring()
                val iv = args.arg(2).checkstring()
                val ct = args.arg(3).checkstring()
                val tag = args.arg(4).checkstring()
                return try {
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        SecretKeySpec(key.m_bytes, key.m_offset, key.m_length, "AES"),
                        GCMParameterSpec(128, iv.m_bytes, iv.m_offset, iv.m_length),
                    )
                    // GCM: the tag goes AFTER the ciphertext.
                    val data = ct.bytes() + tag.bytes()
                    LuaValue.valueOf(cipher.doFinal(data))
                } catch (e: Exception) {
                    LuaValue.NIL
                }
            }
        })

        globals.set("rc4", object : TwoArgFunction() {
            override fun call(keyArg: LuaValue, dataArg: LuaValue): LuaValue {
                val key = keyArg.checkstring()
                val data = dataArg.checkstring()
                if (key.m_length == 0) throw LuaError("rc4: empty key")
                return LuaValue.valueOf(rc4(key.bytes(), data.bytes()))
            }
        })

        // NoveLA LuaSourceLoader.kt:983-1000: aes_decrypt(b64, key, iv) —
        // AES-CBC/PKCS5. The first arg is BASE64 ciphertext decoded by the
        // engine (corpus: seekplayer hexToBase64, m440in poncho.ct, tomatomtl
        // enc); key/iv are raw bytes read WITHOUT a UTF-8 round-trip, which
        // corrupted bytes ≥ 0x80. Output is a UTF-8 string; cipher errors → nil.
        globals.set("aes_decrypt", object : ThreeArgFunction() {
            override fun call(b64Arg: LuaValue, keyArg: LuaValue, ivArg: LuaValue): LuaValue {
                val b64 = b64Arg.checkjstring()
                val key = keyArg.checkstring()
                val iv = ivArg.checkstring()
                return try {
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        SecretKeySpec(key.m_bytes, key.m_offset, key.m_length, "AES"),
                        IvParameterSpec(iv.m_bytes, iv.m_offset, iv.m_length),
                    )
                    LuaValue.valueOf(String(cipher.doFinal(Base64.getDecoder().decode(b64)), Charsets.UTF_8))
                } catch (e: Exception) {
                    LuaValue.NIL
                }
            }
        })

        // NoveLA LuaCipherFunctions.kt:88-117: aes_ctr(data, key, iv) —
        // AES/CTR/NoPadding, raw bytes out. Symmetric (the same call encrypts
        // and decrypts). Key 16/24/32, iv strictly 16 (the AES block — an
        // 8-byte nonce is rejected even though javax.crypto would take it).
        // Bad lengths → LuaError.
        globals.set("aes_ctr", object : ThreeArgFunction() {
            override fun call(dataArg: LuaValue, keyArg: LuaValue, ivArg: LuaValue): LuaValue {
                val data = dataArg.checkstring()
                val key = keyArg.checkstring()
                val iv = ivArg.checkstring()
                if (key.m_length != 16 && key.m_length != 24 && key.m_length != 32) {
                    throw LuaError("aes_ctr: invalid key length ${key.m_length} (expected 16, 24 or 32)")
                }
                if (iv.m_length != 16) {
                    throw LuaError("aes_ctr: invalid iv length ${iv.m_length} (expected 16)")
                }
                return try {
                    val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.ENCRYPT_MODE,
                        SecretKeySpec(key.m_bytes, key.m_offset, key.m_length, "AES"),
                        IvParameterSpec(iv.m_bytes, iv.m_offset, iv.m_length),
                    )
                    // CTR is symmetric: ENCRYPT_MODE also decrypts.
                    LuaValue.valueOf(cipher.doFinal(data.bytes()))
                } catch (e: Exception) {
                    throw LuaError("aes_ctr: ${e.message}")
                }
            }
        })

        // NoveLA LuaCipherFunctions.kt:119-147: rsa_decrypt(data, privateKey) —
        // RSA/ECB/PKCS1Padding; privateKey is standard-base64 DER PKCS#8. Bad
        // base64/key/padding → LuaError (a call error, not data: a nil
        // fallback would hide a wrong key).
        globals.set("rsa_decrypt", object : TwoArgFunction() {
            override fun call(dataArg: LuaValue, keyArg: LuaValue): LuaValue {
                val data = dataArg.checkstring()
                val key = keyArg.checkstring()
                val keyText = String(key.m_bytes, key.m_offset, key.m_length, Charsets.ISO_8859_1)
                val keyBytes = try {
                    // Strict standard base64 (no lenient -_/ mapping here).
                    Base64.getDecoder().decode(keyText.trim())
                } catch (e: IllegalArgumentException) {
                    throw LuaError("rsa_decrypt: invalid base64 private key")
                }
                val privateKey = try {
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                } catch (e: Exception) {
                    throw LuaError("rsa_decrypt: invalid PKCS#8 private key: ${e.message}")
                }
                return try {
                    val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey)
                    LuaValue.valueOf(cipher.doFinal(data.bytes()))
                } catch (e: Exception) {
                    throw LuaError("rsa_decrypt: decryption failed: ${e.message}")
                }
            }
        })

        // NoveLA LuaCipherFunctions.kt:152-177: ephemeral ECDSA P-256 signature
        // (bysekoze server nonce): a fresh KeyPair per call, SHA256withECDSA
        // (WebCrypto {name:"ECDSA", hash:"SHA-256"} equivalent). Returns a
        // table {signature, x, y} — base64url without padding; signature is
        // raw r‖s (64 bytes), x/y are the coordinates without the 0x04 prefix.
        globals.set("ecdsa_sign_p256", object : OneArgFunction() {
            override fun call(messageArg: LuaValue): LuaValue {
                val message = messageArg.checkstring()
                return try {
                    val keyPair = KeyPairGenerator.getInstance("EC").run {
                        initialize(ECGenParameterSpec("secp256r1"))
                        generateKeyPair()
                    }
                    val der = Signature.getInstance("SHA256withECDSA").run {
                        initSign(keyPair.private)
                        update(message.m_bytes, message.m_offset, message.m_length)
                        sign()
                    }
                    val pub = keyPair.public as ECPublicKey
                    val b64 = Base64.getUrlEncoder().withoutPadding()
                    LuaTable().also { table ->
                        table.set("signature", LuaValue.valueOf(b64.encodeToString(ecdsaDerToRaw64(der))))
                        table.set("x", LuaValue.valueOf(b64.encodeToString(fixed32(pub.w.affineX.toByteArray()))))
                        table.set("y", LuaValue.valueOf(b64.encodeToString(fixed32(pub.w.affineY.toByteArray()))))
                    }
                } catch (e: Exception) {
                    throw LuaError("ecdsa_sign_p256: ${e.message}")
                }
            }
        })

        // ── codecs ──────────────────────────────────────────────────────────
        // Lenient canon (NoveLA M5): strip whitespace → -_→+/ → pad → decode;
        // invalid base64 → nil.
        globals.set("base64_decode_bytes", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val s = arg.checkstring()
                val text = String(s.m_bytes, s.m_offset, s.m_length, Charsets.ISO_8859_1)
                val clean = text.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
                val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
                return try {
                    LuaValue.valueOf(Base64.getDecoder().decode(padded))
                } catch (e: IllegalArgumentException) {
                    LuaValue.NIL
                }
            }
        })
        // Standard alphabet, no wrap; input string encoded as UTF-8 bytes
        // (NoveLA Quanben5 precedent).
        globals.set("base64_encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(Base64.getEncoder().encodeToString(arg.checkjstring().toByteArray(Charsets.UTF_8)))
        })

        globals.set("unpack_packed", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val s = arg.checkstring()
                return LuaValue.valueOf(unpackPacked(String(s.m_bytes, s.m_offset, s.m_length, Charsets.UTF_8)))
            }
        })

        globals.set("json_encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(Json.encodeToString(JsonElement.serializer(), arg.toJson(arrayListOf())))
        })

        // zlib by default, optional "gzip" mode (NoveLA ethalon); corrupt or
        // truncated data → nil (caller does `or original`).
        globals.set("inflate", object : TwoArgFunction() {
            override fun call(data: LuaValue, mode: LuaValue): LuaValue {
                val s = data.checkstring()
                val m = if (mode.isnil()) "zlib" else mode.checkjstring()
                if (m != "zlib" && m != "gzip") return LuaValue.NIL
                val input = s.bytes()
                return try {
                    LuaValue.valueOf(if (m == "gzip") inflateGzip(input) else inflateZlib(input))
                } catch (e: Exception) {
                    LuaValue.NIL
                }
            }
        })

        // Ruling: fixed PBKDF2WithHmacSHA256, 4 args.
        globals.set("pbkdf2", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val password = args.arg(1).checkstring()
                val salt = args.arg(2).checkstring()
                val iterations = args.arg(3).checkint()
                val keyLength = args.arg(4).checkint()
                if (iterations < 1) throw LuaError("pbkdf2: iterations must be >= 1, got $iterations")
                if (keyLength < 1) throw LuaError("pbkdf2: keyLength must be >= 1, got $keyLength")
                return try {
                    // spec inside try: dkLen*8 overflow is a call error too.
                    val spec = PBEKeySpec(
                        String(password.m_bytes, password.m_offset, password.m_length, Charsets.UTF_8).toCharArray(),
                        salt.bytes(),
                        iterations,
                        keyLength * 8,
                    )
                    try {
                        LuaValue.valueOf(
                            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                        )
                    } finally {
                        spec.clearPassword()
                    }
                } catch (e: Exception) {
                    throw LuaError("pbkdf2: ${e.message}")
                }
            }
        })
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Raw bytes of a LuaString without any charset round-trip. */
    private fun LuaString.bytes(): ByteArray =
        m_bytes.copyOfRange(m_offset, m_offset + m_length)

    private fun digest(algo: String, s: LuaString): ByteArray {
        val md = MessageDigest.getInstance(algo)
        md.update(s.m_bytes, s.m_offset, s.m_length)
        return md.digest()
    }

    /** Lowercase, 2 chars per byte — NoveLA LuaHashFunctions fileSha256 form. */
    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * NoveLA LuaCipherFunctions.kt:184-201: DER SEQUENCE { INTEGER r, INTEGER s }
     * → raw r‖s (exactly 64 bytes). Single-byte lengths: a P-256 signature is
     * ≤ 72 bytes (short DER form).
     */
    internal fun ecdsaDerToRaw64(der: ByteArray): ByteArray {
        if (der.size < 2 || der[0] != 0x30.toByte()) throw IllegalArgumentException("bad DER: no SEQUENCE")
        if (2 + (der[1].toInt() and 0xFF) != der.size) throw IllegalArgumentException("bad DER: bad SEQUENCE length")
        var p = 2
        fun nextInt(): ByteArray {
            if (p + 2 > der.size || der[p] != 0x02.toByte()) throw IllegalArgumentException("bad DER: no INTEGER")
            val len = der[p + 1].toInt() and 0xFF
            p += 2
            if (p + len > der.size) throw IllegalArgumentException("bad DER: INTEGER overflow")
            val v = der.copyOfRange(p, p + len)
            p += len
            return v
        }
        val r = nextInt()
        val s = nextInt()
        if (p != der.size) throw IllegalArgumentException("bad DER: trailing bytes")
        return fixed32(r) + fixed32(s)
    }

    /**
     * NoveLA LuaCipherFunctions.kt:207-213: unsigned big-endian number → exactly
     * 32 bytes: leading 0x00 trimmed (INTEGER/BigInteger sign bit), short
     * values left-padded.
     */
    internal fun fixed32(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 32 && bytes[start] == 0.toByte()) start++
        val core = bytes.copyOfRange(start, bytes.size)
        if (core.size > 32) throw IllegalArgumentException("integer exceeds 32 bytes")
        return ByteArray(32 - core.size) + core
    }

    /**
     * RC4 by hand (~15 lines): SunJCE rejects keys < 40 bits and the Android
     * provider differs — NoveLA verified this with a 24-bit golden vector.
     */
    internal fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        val out = ByteArray(data.size)
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    private fun inflateZlib(input: ByteArray): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(input)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n > 0) {
                    out.write(buf, 0, n)
                    continue
                }
                if (inf.finished()) break
                throw IOException("inflate: truncated or invalid zlib stream")
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }

    private fun inflateGzip(input: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }

    /** Lua `%w` = [0-9A-Za-z] (no underscore, unlike decode.lua). */
    private val wordPattern = Regex("[0-9A-Za-z]+")

    // Lua patterns → regex. In Lua `(.-)` is lazy and spans `'`, so `.*?`
    // here too (NOT `[^']*`); `.` matches \n like in Lua → DOT_ALL.
    private val patternBrace =
        Regex("""\}\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    private val patternPlain =
        Regex("""\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

    /** Port of NoveLA unpackPacked (ar/anime4up.lua:890) — deterministic, no eval. */
    internal fun unpackPacked(body: String): String {
        if (!body.contains("p,a,c,k,e,d")) return ""
        val m = patternBrace.find(body) ?: patternPlain.find(body) ?: return ""
        val payload = m.groupValues[1]
        val radix = m.groupValues[2].toDoubleOrNull() ?: return ""
        if (radix < 2) return ""
        val dict = m.groupValues[4].split("|")
        return payload.replace(wordPattern) { mr ->
            val v = baseN(mr.value, radix)
            // Lua: dict[v + 1] (1-based) → dict[v] in Kotlin; strict indexing.
            val word = if (v != null && v < dict.size) dict[v.toInt()] else null
            if (word.isNullOrEmpty()) mr.value else word
        }
    }

    /**
     * Token from radix-N notation. Double like Lua numbers (the reference
     * computes in double precision): long tokens fall out of the dictionary
     * range and stay untouched — exactly like Lua, without Int overflow.
     */
    private fun baseN(token: String, radix: Double): Double? {
        var v = 0.0
        for (ch in token) {
            val d = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return null
            }
            if (d >= radix) return null
            v = v * radix + d
        }
        return v
    }

    // ── json_encode via kotlinx.serialization ────────────────────────────────

    /**
     * Recursive conversion; [path] guards against cycles. Tables with
     * contiguous integer keys 1..n become arrays; anything else an object
     * with sorted keys (deterministic output — LuaTable order is unspecified).
     */
    private fun LuaValue.toJson(path: ArrayList<LuaTable>): JsonElement = when (type()) {
        // NoveLA LuaCodecFunctions.kt:108: a caller must never pass nil (nested
        // nil erases the key, so only a top-level nil can arrive here).
        LuaValue.TNIL -> throw LuaError("json_encode: nil is not encodable")
        LuaValue.TBOOLEAN -> JsonPrimitive(toboolean())
        LuaValue.TNUMBER -> numberToJson(todouble())
        LuaValue.TSTRING -> JsonPrimitive(tojstring())
        LuaValue.TTABLE -> checktable().toJson(path)
        else -> throw LuaError("json_encode: unsupported value type ${typename()}")
    }

    private fun LuaTable.toJson(path: ArrayList<LuaTable>): JsonElement {
        if (path.any { it === this }) throw LuaError("json_encode: circular table reference")
        path.add(this)
        try {
            val keys = keys()
            val allNumbers = keys.all { it.type() == LuaValue.TNUMBER }
            val allStrings = keys.all { it.type() == LuaValue.TSTRING }
            if (!allNumbers && !allStrings) {
                throw LuaError("json_encode: mixed or unsupported table keys")
            }
            if (allNumbers && isArrayKeys(keys)) {
                return JsonArray((1..keys.size).map { rawget(it).toJson(path) })
            }
            return JsonObject(
                keys.map { objectKey(it) to rawget(it).toJson(path) }
                    .sortedBy { it.first }
                    .toMap(LinkedHashMap()),
            )
        } finally {
            path.removeAt(path.lastIndex)
        }
    }

    /** Contiguous integers 1..n: keys unique, so {1..n} ⇔ each ∈ [1, n]. */
    private fun isArrayKeys(keys: Array<LuaValue>): Boolean {
        for (k in keys) {
            val d = k.todouble()
            val i = d.toInt()
            if (d != i.toDouble() || i < 1 || i > keys.size) return false
        }
        return true
    }

    private fun objectKey(k: LuaValue): String =
        if (k.type() == LuaValue.TSTRING) k.tojstring() else numberToJson(k.todouble()).content

    private fun numberToJson(d: Double): JsonPrimitive {
        if (d.isNaN() || d.isInfinite()) throw LuaError("json_encode: non-finite number")
        val l = d.toLong()
        return if (l.toDouble() == d) JsonPrimitive(l) else JsonPrimitive(d)
    }
}
