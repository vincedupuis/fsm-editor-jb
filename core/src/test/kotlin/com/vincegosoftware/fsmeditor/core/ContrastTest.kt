package com.vincegosoftware.fsmeditor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diagram's signal colors (the trace palette, the warning and the error colors, as `DiagramCanvas` draws them)
 * made readable by [Contrast.readable] on the backgrounds of the IDE's default themes: WCAG 4.5:1 for text, 3:1 for
 * marks. The plugin's `Theme.mark` and `Theme.textOf` apply it, against a state's fill.
 */
class ContrastTest {
    private fun rgb(c: String) = ElementColors.rgb(c)

    /** Background and text color of the editors of the IDE's and VS Code's default themes. */
    private val themes = listOf(
        Triple("Light", "#ffffff", "#000000"),
        Triple("Darcula", "#2b2b2b", "#a9b7c6"),
        Triple("New UI Dark", "#1e1f22", "#bcbec4"),
        Triple("New UI Light", "#ffffff", "#080808"),
        Triple("High contrast", "#000000", "#ffffff"),
        Triple("VS Code Light Modern", "#ffffff", "#3b3b3b"),
        Triple("VS Code Dark Modern", "#1f1f1f", "#cccccc"),
    )

    /** The trace palette, the warning and the error colors. */
    private val signals = listOf("#388a34", "#1a85ff", "#652d90", "#d18616", "#00a3a3", "#d16da8", "#bf8803", "#e51400")

    @Test
    fun makesTheSignalColorsReadable() {
        for ((theme, background, foreground) in themes) {
            // As Theme does it: against a state's fill (the background moved 5% towards the foreground), the stricter
            // of the two; then readable on the background too.
            val fill = Contrast.mix(rgb(background), rgb(foreground), 0.05)
            for (c in signals) {
                for (min in listOf(Contrast.MARK, Contrast.TEXT)) {
                    val r = Contrast.readable(rgb(c), fill, rgb(foreground), min)
                    assertTrue(Contrast.ratio(r, fill) >= min, "$theme: $c at $min on a state: ${Contrast.ratio(r, fill)}")
                    assertTrue(Contrast.ratio(r, rgb(background)) >= min, "$theme: $c at $min: ${Contrast.ratio(r, rgb(background))}")
                    if (Contrast.ratio(rgb(c), fill) >= min) assertEquals(rgb(c), r, "a color that already stands out is kept")
                }
            }
        }
    }

    @Test
    fun measuresAsWcag() {
        assertEquals(21.0, Contrast.ratio(Triple(0, 0, 0), Triple(255, 255, 255)), 1e-3)
        assertEquals(1.0, Contrast.ratio(Triple(128, 128, 128), Triple(128, 128, 128)), 1e-3)
        // The values that showed the need, in VS Code's Light Modern: its chart green on white, its warning yellow on a state's fill.
        assertTrue(Contrast.ratio(rgb("#388a34"), Triple(255, 255, 255)) in 4.3..4.4)
        assertTrue(Contrast.ratio(rgb("#bf8803"), rgb("#f8f8f8")) in 2.9..3.0)
    }
}
