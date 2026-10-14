package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Memory-hard PoW solver for filemoon/byse hosts. Port of NoveLA PowSolver
 * (gr()/wr()/er() from byse.py, Gujal00/ResolveURL, GPL-3.0).
 *
 * No Thread.sleep: the loop checks an elapsed-time budget every 1024
 * iterations (System.currentTimeMillis — the same clock used by
 * network/cache/Time.kt) and gives up by returning null.
 * ceiling: single-shot budget, no cancellation hook — a host that raises
 * difficulty beyond the budget needs a bigger budgetMs at the call site.
 */
object PowSolver {

    /** Cyclic shift of a 32-bit value. */
    private fun re(t: Int, e: Int): Int = (t shl e) or (t ushr (32 - e))

    /** One state transform step (byse ye). */
    private fun ye(w: IntArray) {
        w[0] = w[0] + w[1]; w[3] = re(w[3] xor w[0], 16)
        w[2] = w[2] + w[3]; w[1] = re(w[1] xor w[2], 12)
        w[0] = w[0] + w[1]; w[3] = re(w[3] xor w[0], 8)
        w[2] = w[2] + w[3]; w[1] = re(w[1] xor w[2], 7)
    }

    internal fun hash(input: ByteArray): IntArray {
        // Constants > Int.MAX — hex + toInt() only (Kotlin would infer Long)
        val e = intArrayOf(1779033703, 0xBB67AE85.toInt(), 1013904242, 0xA54FF53A.toInt())
        val be = 512; val lt = 511; val dr = 2
        val lr = 0x9E3779B1.toInt(); val hr = 0x85EBCA77.toInt()
        for (b in input) {
            e[0] = e[0] + (b.toInt() and 0xFF)
            e[0] = re(e[0], 7)
            ye(e)
        }
        repeat(8) { ye(e) }
        val r = IntArray(be)
        for (i in 0 until be) {
            ye(e)
            r[i] = e[0] xor e[2]
        }
        repeat(dr) {
            for (s in 0 until be) {
                val a = r[s] and lt
                var c = r[s] + r[a]
                c = re(c, 13)
                c = c xor (r[(s + 1) and lt] * lr)
                r[s] = c
                e[0] = e[0] xor c
                ye(e)
            }
        }
        val n = IntArray(8)
        val o = be / 8
        for (i in 0 until 8) {
            ye(e)
            var s = e[0]
            val a = i * o
            for (c in 0 until o) {
                val d = r[a + c]
                s = s + d
                s = re(s, 5)
                s = s xor (d * hr)
            }
            n[i] = s xor e[2]
        }
        return n
    }

    /** wr() from byse.py: leading zeros of the bit representation. */
    internal fun leadingZeros(bits: IntArray): Int {
        var acc = 0
        for (n in bits) {
            if (n == 0) { acc += 32; continue }
            return acc + Integer.numberOfLeadingZeros(n)
        }
        return acc
    }

    /**
     * Finds a nonce: leadingZeros(hash("$challenge:$nonce")) >= difficulty.
     * [budgetMs] is a hard time budget; null = did not make it in time
     * (the caller decides what to do).
     */
    fun solve(challenge: String, difficulty: Int, budgetMs: Long = 15_000): String? {
        if (difficulty <= 0) return "0"
        val start = System.currentTimeMillis()
        val prefix = "$challenge:".toByteArray(Charsets.US_ASCII)
        var s = 0L
        while (true) {
            repeat(1024) {
                val input = prefix + s.toString().toByteArray(Charsets.US_ASCII)
                if (leadingZeros(hash(input)) >= difficulty) return s.toString()
                s++
            }
            if (System.currentTimeMillis() - start > budgetMs) return null
        }
    }

    /** Lua binding: pow_solve(challenge, difficulty) → nonce string or nil. */
    fun install(session: LuaSession) {
        session.globals.set("pow_solve", object : TwoArgFunction() {
            override fun call(challenge: LuaValue, difficulty: LuaValue): LuaValue {
                val nonce = solve(challenge.checkjstring(), difficulty.checkint()) ?: return LuaValue.NIL
                return LuaValue.valueOf(nonce)
            }
        })
    }
}
