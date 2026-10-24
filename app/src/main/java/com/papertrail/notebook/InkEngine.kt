package com.papertrail.notebook

import androidx.compose.ui.geometry.Offset
import kotlin.math.min

/** Small, allocation-conscious geometry helpers used by the live ink renderer. */
internal data class InkSample(val position: Offset, val pressure: Float)

internal object InkEngine {
    /**
     * Centripetal-like Catmull-Rom interpolation. Every raw input segment is rebuilt as a
     * cubic curve, so a fast stylus never produces the visibly polygonal joins of raw events.
     * A little endpoint extrapolation keeps the curve tangent continuous at both ends.
     */
    fun smooth(samples: List<InkSample>, stepsPerSegment: Int = 6): List<InkSample> {
        if (samples.size < 3) return samples
        val output = ArrayList<InkSample>((samples.size - 1) * stepsPerSegment + 1)
        for (i in 0 until samples.lastIndex) {
            val p0 = samples[(i - 1).coerceAtLeast(0)]
            val p1 = samples[i]
            val p2 = samples[i + 1]
            val p3 = samples[(i + 2).coerceAtMost(samples.lastIndex)]
            for (step in 0 until stepsPerSegment) {
                val t = step.toFloat() / stepsPerSegment
                val t2 = t * t
                val t3 = t2 * t
                fun catmull(a: Float, b: Float, c: Float, d: Float): Float =
                    .5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
                val point = Offset(
                    catmull(p0.position.x, p1.position.x, p2.position.x, p3.position.x),
                    catmull(p0.position.y, p1.position.y, p2.position.y, p3.position.y)
                )
                output += InkSample(point, (p1.pressure + (p2.pressure - p1.pressure) * t).coerceIn(.08f, 1f))
            }
        }
        output += samples.last()
        return output
    }

    /** Pressure response tuned for a natural pen: readable hairlines, no sudden width jumps. */
    fun width(base: Float, pressure: Float, sampleIndex: Int, sampleCount: Int): Float {
        val normalized = pressure.coerceIn(.08f, 1f)
        val pressureWidth = .58f + normalized * .84f
        val startTaper = ((sampleIndex + 1) / 2.5f).coerceIn(.78f, 1f)
        val endTaper = ((sampleCount - sampleIndex) / 2.5f).coerceIn(.78f, 1f)
        return (base * pressureWidth * min(startTaper, endTaper)).coerceAtLeast(.65f)
    }

    /** Stabilizer for noisy digitizers. Higher values smooth jitter more, with bounded lag. */
    fun stabilize(previous: Offset, incoming: Offset, amount: Float, speedPxPerMs: Float = 0f): Offset {
        // At high velocity reduce the low-pass amount, preserving the natural low-latency feel.
        val lag = amount.coerceIn(0f, .88f) / (1f + speedPxPerMs.coerceAtLeast(0f) * .35f)
        val follow = (1f - lag).coerceIn(.12f, 1f)
        return Offset(previous.x + (incoming.x - previous.x) * follow, previous.y + (incoming.y - previous.y) * follow)
    }
}
