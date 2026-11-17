package com.vincegosoftware.fsmeditor.editor

import com.vincegosoftware.fsmeditor.core.PaneDiff
import java.awt.Color

/**
 * How the panes of a diagram diff or merge mark their elements, the colors of the VS Code edition's editor.css:
 * added green, removed red, changed amber; a conflict red and dashed, a change Git merged blue, a resolved conflict green.
 */
object DiffMarks {
    class Mark(val color: Color, val vertexWidth: Double, val transitionWidth: Double, val dash: FloatArray? = null)

    private fun hex(c: String) = Color(c.substring(1).toInt(16))

    /** The mark of element [id] in [diff], or null. */
    fun of(diff: PaneDiff?, id: String?): Mark? {
        if (diff == null || id == null) return null
        return when (diff.marks[id]) {
            "added" -> Mark(hex("#2ea043"), 3.0, 2.6)
            "removed" -> Mark(hex("#e5534b"), 3.0, 2.6)
            "changed" -> Mark(hex("#d29922"), 3.0, 2.6)
            "conflict" -> Mark(hex("#e5534b"), 3.0, 2.6, floatArrayOf(6f, 3f))
            "merged" -> Mark(hex("#539bf5"), 2.0, 2.0)
            "resolved" -> Mark(hex("#2ea043"), 2.0, 2.0)
            else -> null
        }
    }
}
