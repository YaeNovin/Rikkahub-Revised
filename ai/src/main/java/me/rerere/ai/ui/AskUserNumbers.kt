package me.rerere.ai.ui

import java.math.BigDecimal
import java.math.RoundingMode

object AskUserNumbers {
    fun format(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    fun snapFraction(minimum: Double, maximum: Double, step: Double, fraction: Float): String {
        val min = BigDecimal.valueOf(minimum)
        val range = BigDecimal.valueOf(maximum).subtract(min)
        val increment = BigDecimal.valueOf(step)
        val last = range.divide(increment, 0, RoundingMode.FLOOR)
        val index = range.multiply(BigDecimal.valueOf(fraction.coerceIn(0f, 1f).toDouble()))
            .divide(increment, 0, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, last)
        return min.add(index.multiply(increment)).stripTrailingZeros().toPlainString()
    }

    fun isStepAligned(value: Double, minimum: Double, step: Double): Boolean {
        if (!value.isFinite() || !minimum.isFinite() || !step.isFinite() || step <= 0) return false
        val increment = BigDecimal.valueOf(step)
        val delta = BigDecimal.valueOf(value).subtract(BigDecimal.valueOf(minimum))
        val nearest = delta.divide(increment, 0, RoundingMode.HALF_UP).multiply(increment)
        return delta.subtract(nearest).abs() <= increment.multiply(BigDecimal("0.0000001"))
    }
}
