package com.vincegosoftware.fsmeditor.editor

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.vincegosoftware.fsmeditor.core.ElementColors
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
class Theme private constructor(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val panel: Color,
    val isDark: Boolean,
    line: Color? = null,
    stateFill: Color? = null,
    noteFill: Color? = null,
) {
    val border: Color = mix(background, foreground, 0.22)
    val line: Color = line ?: mix(background, foreground, 0.85)
    val stateFill: Color = stateFill ?: mix(background, foreground, 0.05)
    val noteFill: Color = noteFill ?: mix(this.stateFill, Color(0xe8, 0xc5, 0x47), 0.22)
    val grid: Color = mix(background, foreground, 0.14)
    val widget: Color = mix(background, foreground, 0.06)
    val error = Color(0xe5, 0x14, 0x00)
    val warning = Color(0xbf, 0x88, 0x03)
    val info = Color(0x1a, 0x85, 0xff)

    /** Fill of a colored shape: a light tint of its color over the shape background. */
    fun tint(c: Color, background: Color): Color = mix(background, c, ElementColors.TINT_AMOUNT)

    fun alpha(c: Color, opacity: Double): Color = Color(c.red, c.green, c.blue, (c.alpha * opacity).roundToInt().coerceIn(0, 255))

    fun stroke(width: Double, dashes: FloatArray? = null): BasicStroke =
        if (dashes == null) BasicStroke(width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        else BasicStroke(width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dashes, 0f)

    companion object {
        private var cached: Theme? = null

        val current: Theme
            get() = cached ?: Theme(
                background = EditorColorsManager.getInstance().globalScheme.defaultBackground,
                foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground,
                muted = UIUtil.getContextHelpForeground(),
                accent = JBUI.CurrentTheme.Focus.focusColor(),
                panel = UIUtil.getPanelBackground(),
                isDark = !JBColor.isBright(),
            ).also { cached = it }

        /** The light colors of the SVG export, whatever the IDE theme: for the PNG export. */
        val export: Theme by lazy {
            Theme(
                background = Color.WHITE,
                foreground = Color(0x1f, 0x1f, 0x1f),
                muted = Color(0x6f, 0x6f, 0x6f),
                accent = Color(0x00, 0x78, 0xd4),
                panel = Color.WHITE,
                isDark = false,
                line = Color(0x33, 0x33, 0x33),
                stateFill = Color(0xfd, 0xfd, 0xfd),
                noteFill = Color(0xfb, 0xf3, 0xcf),
            )
        }

        /** Forgets the colors after a theme or color scheme change. */
        fun reset() {
            cached = null
        }

        /** A diagram color of the model (`#rrggbb`), or null when there is none. */
        fun colorOf(hex: String?): Color? {
            val c = ElementColors.of(hex) ?: return null
            val (r, g, b) = ElementColors.rgb(c)
            return Color(r, g, b)
        }

        /** [a] moved towards [b] by [k] (0..1). */
        fun mix(a: Color, b: Color, k: Double) = Color(
            (a.red + (b.red - a.red) * k).roundToInt(),
            (a.green + (b.green - a.green) * k).roundToInt(),
            (a.blue + (b.blue - a.blue) * k).roundToInt(),
        )
    }
}
