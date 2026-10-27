package dev.cdav.overscantoggle

/**
 * Grid snapping in tenths of a percent. A step moves to the next multiple of [step], not "current + step",
 * so an off-grid value like 14.3% goes to 14.5% on +0.5% and to 14.0% on -0.5%, then stays on the grid.
 */
object Snap {
    /** Next multiple of [step] strictly above [v]. */
    fun up(v: Int, step: Int): Int = (v / step + 1) * step

    /** Next multiple of [step] strictly below [v]. */
    fun down(v: Int, step: Int): Int = if (v % step == 0) v - step else (v / step) * step
}
