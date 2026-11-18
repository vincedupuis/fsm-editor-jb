package com.vincegosoftware.fsmeditor.core

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/** Number helpers matching how the diagram and the files have always printed coordinates. */
object Num {
    /** Rounds half up (towards +∞), like the rounding used for saved coordinates. */
    fun round(n: Double): Double = floor(n + 0.5)

    /** Rounds to 0.1. */
    fun r1(n: Double): Double = floor(n * 10 + 0.5) / 10

    /**
     * JavaScript's `Math.hypot(a, b)` as V8 computes it (scaled by the larger value, with Kahan summation),
     * which differs from `sqrt(a * a + b * b)` in the last bit for many values.
     */
    fun hypot(a: Double, b: Double): Double {
        val x0 = abs(a)
        val x1 = abs(b)
        if (x0.isInfinite() || x1.isInfinite()) return Double.POSITIVE_INFINITY
        if (x0.isNaN() || x1.isNaN()) return Double.NaN
        val max = maxOf(x0, x1)
        if (max == 0.0) return 0.0
        var sum = 0.0
        var compensation = 0.0
        for (x in doubleArrayOf(x0, x1)) {
            val r = x / max
            val summand = r * r - compensation
            val preliminary = sum + summand
            compensation = preliminary - sum - summand
            sum = preliminary
        }
        return sqrt(sum) * max
    }

    /**
     * The text JavaScript's `String(n)` gives: the shortest digits that read back as [n],
     * `60`, `12.5`, `1e-7`, `1.5e+21`; `-0` gives `0`. NaN and infinities give `0`.
     * Computed exactly, so it doesn't depend on the JDK's own formatting.
     */
    fun format(n: Double): String {
        if (n.isNaN() || n.isInfinite()) return "0"
        if (n == 0.0) return "0"
        if (n == floor(n) && abs(n) < 1e15) return n.toLong().toString()
        if (n < 0) return "-" + format(-n)
        val (digits, point) = shortestDigits(n)
        return layout(digits, point)
    }

    /** JavaScript's `String(n)`, `NaN` and `Infinity` included. */
    fun js(n: Double): String =
        if (n.isNaN()) "NaN" else if (n == Double.POSITIVE_INFINITY) "Infinity" else if (n == Double.NEGATIVE_INFINITY) "-Infinity" else format(n)

    /**
     * Shortest digits (no trailing zero) such that 0.digits × 10^point reads back as [n] (> 0),
     * the closest to [n] when several do (ECMAScript Number::toString).
     */
    private fun shortestDigits(n: Double): Pair<String, Int> {
        val bits = n.toRawBits()
        val biased = ((bits shr 52) and 0x7FFL).toInt()
        val fraction = bits and 0xFFFFFFFFFFFFFL
        val f = if (biased == 0) fraction else fraction or (1L shl 52)
        val e = if (biased == 0) -1074 else biased - 1075
        // n = f × 2^e. In units of 2^(e-2): n is 4f, and the numbers that read back as n lie
        // between 4f-2 (4f-1 just above a power of two) and 4f+2, the bounds included when f is even.
        val v = BigInteger.valueOf(f).shiftLeft(2)
        val hi = v + BigInteger.valueOf(2)
        val lo = v - BigInteger.valueOf(if (fraction == 0L && biased > 1) 1L else 2L)
        val inclusive = (f and 1L) == 0L
        val twoUp = if (e - 2 >= 0) BigInteger.ONE.shiftLeft(e - 2) else BigInteger.ONE
        val twoDown = if (e - 2 < 0) BigInteger.ONE.shiftLeft(2 - e) else BigInteger.ONE
        fun pow10(x: Int): BigInteger = if (x > 0) BigInteger.TEN.pow(x) else BigInteger.ONE

        // Decimal exponent k with 10^k <= n < 10^(k+1).
        fun atLeastPow10(x: Int) = v * twoUp * pow10(-x) >= twoDown * pow10(x)
        var k = floor(log10(n)).toInt()
        while (!atLeastPow10(k)) k--
        while (atLeastPow10(k + 1)) k++

        for (p in 1..17) {
            // Candidates with p digits are N × 10^s; n / 10^s = num / den.
            val s = k - p + 1
            val scale = twoUp * pow10(-s)
            val den = twoDown * pow10(s)
            val numLo = lo * scale
            val numHi = hi * scale
            val (down, rem) = (v * scale).divideAndRemainder(den)
            val up = down + BigInteger.ONE
            fun reads(c: BigInteger): Boolean {
                val x = c * den
                return if (inclusive) x >= numLo && x <= numHi else x > numLo && x < numHi
            }
            val downOk = reads(down)
            val upOk = reads(up)
            if (!downOk && !upOk) continue
            val chosen = if (downOk && upOk) {
                val c = rem.shiftLeft(1).compareTo(den)
                if (c < 0) down else if (c > 0) up else if (!down.testBit(0)) down else up
            } else if (downOk) down else up
            val text = chosen.toString()
            return text.trimEnd('0') to s + text.length
        }
        // 17 digits always identify a double.
        throw IllegalStateException("No round-trip digits for $n")
    }

    /** Lays out 0.[d] × 10^[n] as ECMAScript Number::toString does. */
    private fun layout(d: String, n: Int): String {
        val k = d.length
        if (n in k..21) return d + "0".repeat(n - k)
        if (n in 1..21) return d.substring(0, n) + "." + d.substring(n)
        if (n in -5..0) return "0." + "0".repeat(-n) + d
        val exp = n - 1
        val tail = "e" + (if (exp < 0) "-" else "+") + abs(exp)
        return if (k == 1) d + tail else d.substring(0, 1) + "." + d.substring(1) + tail
    }

    /** A decimal literal: digits with an optional point and exponent (not the other forms the JVM would accept). */
    private val DECIMAL = Regex("^(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")

    /**
     * Parses a number like JavaScript's `+s || 0` does for XMI attributes (missing or invalid gives 0), infinite
     * ones giving 0 too, as they do once the other editions' JSON has made them null.
     */
    fun parse(s: String?): Double {
        val d = parseJs(s)
        return if (d.isNaN() || d.isInfinite()) 0.0 else d
    }

    /**
     * JavaScript's unary plus (`+s`): the number in [s] once trimmed, 0 when that is empty,
     * NaN when [s] is null (a missing attribute) or isn't a number.
     */
    fun parseJs(s: String?): Double {
        if (s == null) return Double.NaN
        val t = JsWhitespace.trim(s)
        if (t.isEmpty()) return 0.0
        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix > 0) {
                var n = 0.0
                for (i in 2 until t.length) {
                    val c = t[i]
                    val digit = when (c) {
                        in '0'..'9' -> c - '0'
                        in 'a'..'z' -> c - 'a' + 10
                        in 'A'..'Z' -> c - 'A' + 10
                        else -> 99
                    }
                    if (digit >= radix) return Double.NaN
                    n = n * radix + digit
                }
                return n
            }
        }
        val body = if (t[0] == '+' || t[0] == '-') t.substring(1) else t
        if (body == "Infinity") return if (t[0] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        if (!DECIMAL.matches(body)) return Double.NaN
        // Numbers too large for a double read as infinite, as in JavaScript.
        return t.toDouble()
    }
}
