package com.vincegosoftware.fsmeditor.editor

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Colors of the diagram, taken from the current IDE theme and editor color
 * scheme. A new instance is made when the theme changes.
 */
class Theme private constructor() {
    val background: Color = EditorColorsManager.getInstance().globalScheme.defaultBackground
    val foreground: Color = EditorColorsManager.getInstance().globalScheme.defaultForeground
    val muted: Color = UIUtil.getContextHelpForeground()
    val accent: Color = JBUI.CurrentTheme.Focus.focusColor()
    val panel: Color = UIUtil.getPanelBackground()
    val border: Color = mix(background, foreground, 0.22)
    val line: Color = mix(background, foreground, 0.85)
    val stateFill: Color = mix(background, foreground, 0.05)
    val noteFill: Color = mix(stateFill, Color(0xe8, 0xc5, 0x47), 0.22)
    val grid: Color = mix(background, foreground, 0.14)
    val widget: Color = mix(background, foreground, 0.06)
    val isDark: Boolean = !JBColor.isBright()
    val error = Color(0xe5, 0x14, 0x00)
    val warning = Color(0xbf, 0x88, 0x03)
    val info = Color(0x1a, 0x85, 0xff)

    fun alpha(c: Color, opacity: Double): Color = Color(c.red, c.green, c.blue, (c.alpha * opacity).roundToInt().coerceIn(0, 255))

    fun stroke(width: Double, dashes: FloatArray? = null): BasicStroke =
        if (dashes == null) BasicStroke(width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        else BasicStroke(width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dashes, 0f)

    companion object {
        private var cached: Theme? = null

        val current: Theme get() = cached ?: Theme().also { cached = it }

        /** Forgets the colors after a theme or color scheme change. */
        fun reset() {
            cached = null
        }

        /** [a] moved towards [b] by [k] (0..1). */
        fun mix(a: Color, b: Color, k: Double) = Color(
            (a.red + (b.red - a.red) * k).roundToInt(),
            (a.green + (b.green - a.green) * k).roundToInt(),
            (a.blue + (b.blue - a.blue) * k).roundToInt(),
        )
    }
}
