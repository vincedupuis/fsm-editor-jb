package com.vincegosoftware.fsmeditor.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Contrast of the diagram's signal colors (problems, the Trace Player) with the theme's background, as WCAG 2.1
 * measures it: at least 4.5:1 for text (1.4.3), 3:1 for the lines and shapes that carry meaning (1.4.11). The colors
 * are fixed, the themes aren't: a color too close to the background moves towards the foreground until it is
 * readable, as the VS Code edition does for light themes.
 */
object Contrast {
    /** Text, as a transition's label in a trace color. */
    const val TEXT = 4.5

    /** Lines and shapes that carry meaning: problem and trace marks. */
    const val MARK = 3.0

    /** The contrast ratio of two colors, from 1 to 21. */
    fun ratio(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Relative luminance (WCAG 2.1). */
    fun luminance(c: Triple<Int, Int, Int>): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.first) + 0.7152 * channel(c.second) + 0.0722 * channel(c.third)
    }

    /**
     * [color] when it stands out from [background] by [min], else that color moved towards [foreground] (by steps of
     * 5%) until it does, or the foreground.
     */
    fun readable(color: Triple<Int, Int, Int>, background: Triple<Int, Int, Int>, foreground: Triple<Int, Int, Int>, min: Double): Triple<Int, Int, Int> {
        for (k in 0..20) {
            val c = mix(color, foreground, k / 20.0)
            if (ratio(c, background) >= min) return c
        }
        return foreground
    }

    /** [a] moved towards [b] by [k] (0..1), rounded as C#'s Math.Round (to even). */
    fun mix(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>, k: Double): Triple<Int, Int, Int> {
        fun m(x: Int, y: Int) = Math.rint(x + (y - x) * k).toInt()
        return Triple(m(a.first, b.first), m(a.second, b.second), m(a.third, b.third))
    }
}
