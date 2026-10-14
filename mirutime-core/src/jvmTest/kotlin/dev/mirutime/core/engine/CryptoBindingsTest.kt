package dev.mirutime.core.engine

import dev.mirutime.core.engine.binding.CryptoBindings
import dev.mirutime.core.engine.binding.PowSolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Golden vectors ported from NoveLA HeavyOpsTest / PowSolverTest.
// Crypto functions return RAW bytes (binary-str), not hex — the NoveLA
// contract. The LuaCallResult boundary decodes strings as UTF-8, so binary
// results are compared INSIDE Lua via string.char (byte-exact, no boundary
// round-trip); text results cross the boundary normally.
class CryptoBindingsTest {

    private fun eval(code: String): LuaCallResult {
        val session = createLuaSession("eval", code)
        CryptoBindings.install(session)
        PowSolver.install(session)
        return session.call("main", emptyList())
    }

    /** ByteArray → Lua `string.char(b1,b2,…)` literal for in-Lua comparison. */
    private fun luaChar(b: ByteArray): String =
        b.joinToString(",") { (it.toInt() and 0xFF).toString() }.let { "string.char($it)" }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertLuaOk(r: LuaCallResult) {
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("ok"), r.data)
    }

    // ── hashes: raw bytes, compared inside Lua ───────────────────────────────

    @Test
    fun `sha256 of abc matches FIPS golden as raw bytes`() {
        assertLuaOk(
            eval(
                """
                function main()
                  local out = sha256("abc")
                  local exp = ${luaChar("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToBytes())}
                  return (#out == 32 and out == exp) and "ok" or ("bad len=" .. #out)
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `md5 of abc matches RFC 1321 golden as raw bytes`() {
        assertLuaOk(
            eval(
                """
                function main()
                  local out = md5("abc")
                  local exp = ${luaChar("900150983cd24fb0d6963f7d28e17f72".hexToBytes())}
                  return (#out == 16 and out == exp) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `hmac_sha256 RFC 4231 test case 2`() {
        assertLuaOk(
            eval(
                """
                function main()
                  local out = hmac_sha256("Jefe", "what do ya want for nothing?")
                  local exp = ${luaChar("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843".hexToBytes())}
                  return (#out == 32 and out == exp) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `sha1 of abc matches FIPS golden as raw bytes`() {
        assertLuaOk(
            eval(
                """
                function main()
                  local out = sha1("abc")
                  local exp = ${luaChar("a9993e364706816aba3e25717850c26c9cd0d89d".hexToBytes())}
                  return (#out == 20 and out == exp) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `sha1 hex mode returns 40-char hex`() {
        val r = eval("""function main() return sha1("abc", "hex") end""")
        check(r.error == null) { r.error!!.toString() }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `sha1 raw mode matches default and unknown mode throws`() {
        assertLuaOk(
            eval(
                """
                function main()
                  return sha1("abc", "raw") == sha1("abc") and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
        val r = eval("""function main() return sha1("abc", "base64") end""")
        assertTrue("expected error for unknown sha1 mode, got ${r.data}", r.error != null)
    }

    @Test
    fun `crc32 golden vectors`() {
        val r = eval(
            """
            function main()
              return crc32("123456789") .. "|" .. crc32("")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals("3421780262|0", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `binary argument survives byte-exact for sha256`() {
        // All bytes 0..255 as input; expected digest computed by MessageDigest
        // in the test (independent of the binding code path).
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(256) { it.toByte() })
        assertLuaOk(
            eval(
                """
                function main()
                  local t = {}
                  for i = 0, 255 do t[i + 1] = string.char(i) end
                  local out = sha256(table.concat(t))
                  return out == ${luaChar(expected)} and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    // ── ciphers ──────────────────────────────────────────────────────────────

    @Test
    fun `rc4 golden vector Key Plaintext`() {
        assertLuaOk(
            eval(
                """
                function main()
                  local out = rc4("Key", "Plaintext")
                  return out == ${luaChar("bbf316e8d940af0ad3".hexToBytes())} and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `rc4 empty key throws error`() {
        val r = eval(
            """
            function main()
              return rc4(string.char(), "x")
            end
            """.trimIndent()
        )
        assertTrue("expected error for empty key, got ${r.data}", r.error != null)
    }

    @Test
    fun `rc4 is involution on binary data`() {
        assertLuaOk(
            eval(
                """
                function main()
                  local key = string.rep(string.char(160), 4)
                  local data = string.rep(string.char(200), 64)
                  local once = rc4(key, data)
                  return rc4(key, once) == data and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `aes_gcm_decrypt NIST SP800-38D test case 2 with separate tag`() {
        // K=00×16, IV=00×12, P=00×16 → ct/tag from GCM spec (McGrew/Viega).
        // NoveLA shape: 4-arg (key, iv, ct, tag) — raw bytes (wtrlab:122).
        assertLuaOk(
            eval(
                """
                function main()
                  local key = string.rep(string.char(0), 16)
                  local iv = string.rep(string.char(0), 12)
                  local ct = string.char(3,136,218,206,96,182,163,146,243,40,194,185,113,178,254,120)
                  local tag = string.char(171,110,71,212,44,236,19,189,245,58,103,178,18,87,189,223)
                  local out = aes_gcm_decrypt(key, iv, ct, tag)
                  return out == string.rep(string.char(0), 16) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `aes_gcm_decrypt returns nil on wrong key`() {
        val r = eval(
            """
            function main()
              local key = string.rep(string.char(1), 16)
              local iv = string.rep(string.char(0), 12)
              local ct = string.char(3,136,218,206,96,182,163,146,243,40,194,185,113,178,254,120)
              local tag = string.char(171,110,71,212,44,236,19,189,245,58,103,178,18,87,189,223)
              return aes_gcm_decrypt(key, iv, ct, tag) or "nil"
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("nil"), r.data)
    }

    @Test
    fun `aes_decrypt NoveLA shape b64 key iv roundtrip`() {
        // NoveLA LuaSourceLoader.kt:983-1000: aes_decrypt(b64, key, iv) — the
        // engine base64-decodes the first arg (corpus: seekplayer:77,
        // m440in:128). Encrypt in test, decrypt via binding.
        val key = ByteArray(16) { (0x80 + it).toByte() }
        val iv = ByteArray(16) { (0xF0 + (it and 0x0F)).toByte() }
        val plain = "seekplayer payload".toByteArray(Charsets.UTF_8)
        val enc = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
        }.doFinal(plain)
        val b64 = java.util.Base64.getEncoder().encodeToString(enc)
        val r = eval(
            """
            function main()
              local key = ${luaChar(key)}
              local iv = ${luaChar(iv)}
              return aes_decrypt("$b64", key, iv)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals("seekplayer payload", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `aes_decrypt returns nil on wrong key`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { it.toByte() }
        val enc = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
        }.doFinal("payload".toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getEncoder().encodeToString(enc)
        val r = eval(
            """
            function main()
              local key = ${luaChar(ByteArray(16) { 9 })}
              local iv = ${luaChar(iv)}
              return aes_decrypt("$b64", key, iv) or "nil"
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("nil"), r.data)
    }

    @Test
    fun `aes_decrypt returns nil on invalid base64`() {
        val r = eval(
            """
            function main()
              local key = string.rep(string.char(0), 16)
              local iv = string.rep(string.char(0), 16)
              return aes_decrypt("!!!not-base64!!!", key, iv) or "nil"
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("nil"), r.data)
    }

    @Test
    fun `aes_ctr is symmetric and validates lengths`() {
        // NoveLA LuaCipherFunctions.kt:88-117: aes_ctr(data, key, iv), key
        // 16/24/32, iv strictly 16. Encrypt in test, decrypt via binding.
        val key = ByteArray(16) { (it * 7).toByte() }
        val iv = ByteArray(16) { (it * 13).toByte() }
        val plain = "ctr payload with binary \u0000\u00ff tail".toByteArray(Charsets.ISO_8859_1)
        val enc = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
        }.doFinal(plain)
        assertLuaOk(
            eval(
                """
                function main()
                  local key = ${luaChar(key)}
                  local iv = ${luaChar(iv)}
                  local dec = aes_ctr(${luaChar(enc)}, key, iv)
                  -- and back: binding-encrypt must match javax encryption
                  local reenc = aes_ctr(dec, key, iv)
                  return (dec == ${luaChar(plain)} and reenc == ${luaChar(enc)}) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `aes_ctr rejects bad key and iv lengths`() {
        val badKey = eval(
            """
            function main()
              return aes_ctr("data", string.rep(string.char(1), 15), string.rep(string.char(2), 16))
            end
            """.trimIndent()
        )
        assertTrue("expected error for 15-byte key, got ${badKey.data}", badKey.error != null)
        val badIv = eval(
            """
            function main()
              return aes_ctr("data", string.rep(string.char(1), 16), string.rep(string.char(2), 8))
            end
            """.trimIndent()
        )
        assertTrue("expected error for 8-byte iv, got ${badIv.data}", badIv.error != null)
    }

    @Test
    fun `rsa_decrypt roundtrip with generated keypair`() {
        // NoveLA LuaCipherFunctions.kt:119-147: rsa_decrypt(data, privateKey),
        // privateKey = standard-base64 DER PKCS#8.
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }
        val privB64 = java.util.Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val ct = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, keyPair.public)
        }.doFinal("rsa secret".toByteArray(Charsets.UTF_8))
        // 256-byte ciphertext: split string.char into chunks (one 256-arg
        // call exceeds LuaJ's register window).
        val chunks = ct.toList().chunked(64).joinToString(" .. ") { part ->
            "string.char(${part.joinToString(",") { (it.toInt() and 0xFF).toString() }})"
        }
        assertLuaOk(
            eval(
                """
                function main()
                  local out = rsa_decrypt($chunks, "$privB64")
                  return out == "rsa secret" and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `rsa_decrypt rejects bad base64 and bad padding`() {
        val badB64 = eval("""function main() return rsa_decrypt("x", "!!!") end""")
        assertTrue("expected error for bad base64 key, got ${badB64.data}", badB64.error != null)
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }
        val privB64 = java.util.Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val badCt = eval(
            """
            function main()
              return rsa_decrypt(string.rep(string.char(7), 256), "$privB64")
            end
            """.trimIndent()
        )
        assertTrue("expected error for bad padding, got ${badCt.data}", badCt.error != null)
    }

    @Test
    fun `ecdsa_sign_p256 returns bysekoze-shaped table`() {
        // NoveLA LuaCipherFunctions.kt:152-177: {signature, x, y} as
        // base64url-no-pad; signature = raw r‖s (64 bytes), x/y = 32 bytes.
        val r = eval(
            """
            function main()
              local okSig, sig = pcall(ecdsa_sign_p256, "server-nonce")
              if not okSig or type(sig) ~= "table" then return "bad-call" end
              return sig.signature .. "|" .. sig.x .. "|" .. sig.y
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        val parts = (r.data as LuaData.LuaStr).value.split("|")
        check(parts.size == 3) { r.data.toString() }
        val dec = java.util.Base64.getUrlDecoder()
        assertEquals(64, dec.decode(parts[0]).size)
        assertEquals(32, dec.decode(parts[1]).size)
        assertEquals(32, dec.decode(parts[2]).size)
    }

    // ── codecs ───────────────────────────────────────────────────────────────

    @Test
    fun `base64_decode_bytes returns raw binary with NUL`() {
        assertLuaOk(
            eval(
                """
                function main()
                  return base64_decode_bytes("AAECAw==") == string.char(0, 1, 2, 3) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `base64_decode_bytes is lenient`() {
        assertLuaOk(
            eval(
                """
                function main()
                  -- no padding
                  if base64_decode_bytes("AAECAw") ~= string.char(0, 1, 2, 3) then return "bad-nopad" end
                  -- whitespace stripped
                  if base64_decode_bytes("  AAE\nCAw== ") ~= string.char(0, 1, 2, 3) then return "bad-ws" end
                  -- url-safe alphabet -_/ → +/
                  if base64_decode_bytes("_-4=") ~= string.char(255, 238) then return "bad-url" end
                  return "ok"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `base64_decode_bytes invalid input returns nil`() {
        val r = eval("""function main() return base64_decode_bytes("!!!") or "nil" end""")
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("nil"), r.data)
    }

    @Test
    fun `base64_encode roundtrip with decode_bytes`() {
        val r = eval(
            """
            function main()
              local enc = base64_encode("hello, мир")
              local dec = base64_decode_bytes(enc)
              return enc .. "|" .. dec
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        val expected = java.util.Base64.getEncoder()
            .encodeToString("hello, мир".toByteArray(Charsets.UTF_8))
        assertEquals("$expected|hello, мир", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `unpack_packed golden multi_match_escape`() {
        // NoveLA UnpackGoldenFixtures: first packed call wins, escapes
        // \' and \\ are NOT unescaped by the reference.
        val r = eval(
            """
            function main()
              local input = "<script>eval(function(p,a,c,k,e,d){return p}('var s=\\'hi\\'; 0 1 \\\\x',8,2,'first|second'.split('|'),0,0));\neval(function(p,a,c,k,e,d){return p}('0 1',2,2,'third|fourth'.split('|'),0,0))</script>"
              return unpack_packed(input)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals("var s=\\'hi\\'; first second \\\\x", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `unpack_packed golden long_word keeps tokens out of dictionary`() {
        val r = eval(
            """
            function main()
              local input = "eval(function(p,a,c,k,e,d){return p}('0 1 123456789012345 someverylongidentifier123 2',10,5,'zero|one|two|three|four'.split('|'),0,0))"
              return unpack_packed(input)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(
            "zero one 123456789012345 someverylongidentifier123 two",
            (r.data as LuaData.LuaStr).value,
        )
    }

    @Test
    fun `unpack_packed returns empty string when packer absent`() {
        val r = eval(
            """function main() return unpack_packed("plain <script>html</script>") end"""
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr(""), r.data)
    }

    @Test
    fun `json_encode nested golden compact and sorted`() {
        val r = eval(
            """
            function main()
              local root = { n = 1, ok = true, user = { "a", "b" } }
              return json_encode(root)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals("""{"n":1,"ok":true,"user":["a","b"]}""", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `json_encode roundtrip parses back to same structure`() {
        val r = eval(
            """
            function main()
              local t = { title = "Глава 1", num = 42, tags = { "a", "b" }, nested = { x = false } }
              return json_encode(t)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        val json = (r.data as LuaData.LuaStr).value
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("Глава 1", parsed["title"]!!.jsonPrimitive.content)
        assertEquals(42.0, parsed["num"]!!.jsonPrimitive.double, 0.0)
        assertEquals(listOf("a", "b"), parsed["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, parsed["nested"]!!.jsonObject["x"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `json_encode rejects top-level nil`() {
        // NoveLA LuaCodecFunctions.kt:108: nil is not encodable.
        val r = eval("""function main() return json_encode(nil) end""")
        assertTrue("expected error for json_encode(nil), got ${r.data}", r.error != null)
    }

    @Test
    fun `json_encode rejects cyclic tables`() {
        val r = eval(
            """
            function main()
              local t = {}
              t.self = t
              return json_encode(t)
            end
            """.trimIndent()
        )
        assertTrue("expected error for cyclic table, got ${r.data}", r.error != null)
    }

    @Test
    fun `inflate zlib fixture returns original string`() {
        val zlibFox = "789c0bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228014ae72456552aa4e4a703005bdc0fda"
        val r = eval(
            """
            function main()
              local bytes = ${luaChar(zlibFox.hexToBytes())}
              return inflate(bytes)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals("The quick brown fox jumps over the lazy dog", (r.data as LuaData.LuaStr).value)
    }

    @Test
    fun `inflate corrupted data returns nil`() {
        val r = eval(
            """
            function main()
              return inflate(string.char(0, 1, 2, 3)) or "nil"
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("nil"), r.data)
    }

    // ── pbkdf2 ───────────────────────────────────────────────────────────────

    @Test
    fun `pbkdf2 RFC 7914 sha256 test vector`() {
        val expected = (
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
            ).hexToBytes()
        assertLuaOk(
            eval(
                """
                function main()
                  local out = pbkdf2("passwd", "salt", 1, 64)
                  return (#out == 64 and out == ${luaChar(expected)}) and "ok" or "bad"
                end
                """.trimIndent()
            )
        )
    }

    @Test
    fun `pbkdf2 honors keyLength`() {
        val r = eval("""function main() return #pbkdf2("password", "salt", 2, 16) end""")
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaNum(16.0), r.data)
    }

    @Test
    fun `pbkdf2 rejects iterations below one`() {
        val r = eval("""function main() return pbkdf2("password", "salt", 0, 16) end""")
        assertTrue("expected error for iterations=0, got ${r.data}", r.error != null)
    }

    // ── pow_solve ────────────────────────────────────────────────────────────

    @Test
    fun `pow_solve golden nonce for novela difficulty 12`() {
        val r = eval("""function main() return pow_solve("novela", 12) end""")
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("2735"), r.data)
    }

    @Test
    fun `pow_solve difficulty four returns quickly with valid nonce`() {
        val r = eval(
            """
            function main()
              local nonce = pow_solve("challenge", 4)
              return nonce ~= nil and #nonce > 0
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaBool(true), r.data)
    }

    @Test
    fun `pow_solve difficulty zero returns 0`() {
        val r = eval("""function main() return pow_solve("anything", 0) end""")
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("0"), r.data)
    }

    @Test
    fun `pow solver budget exceeded returns null`() {
        assertNull(PowSolver.solve("novela", 40, budgetMs = 20))
    }
}
