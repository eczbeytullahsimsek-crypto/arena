package com.papertrail.notebook

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class InkSample(val position: Offset, val pressure: Float)

/** Fast tablet ink geometry. Smooth centerline + one filled outline path = a few GPU draw calls. */
internal object InkEngine {
    /** Catmull–Rom resampling with a deliberately small subdivision count for live 120 Hz input. */
    fun smooth(samples: List<InkSample>, stepsPerSegment: Int = 2): List<InkSample> {
        if (samples.size < 3) return samples
        val steps = stepsPerSegment.coerceIn(1, 4)
        val output = ArrayList<InkSample>((samples.size - 1) * steps + 1)
        for (i in 0 until samples.lastIndex) {
            val p0 = samples[(i - 1).coerceAtLeast(0)]
            val p1 = samples[i]
            val p2 = samples[i + 1]
            val p3 = samples[(i + 2).coerceAtMost(samples.lastIndex)]
            for (step in 0 until steps) {
                val t = step.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                fun curve(a: Float, b: Float, c: Float, d: Float): Float =
                    .5f * (2f*b + (-a+c)*t + (2f*a-5f*b+4f*c-d)*t2 + (-a+3f*b-3f*c+d)*t3)
                output += InkSample(
                    Offset(curve(p0.position.x,p1.position.x,p2.position.x,p3.position.x), curve(p0.position.y,p1.position.y,p2.position.y,p3.position.y)),
                    (p1.pressure + (p2.pressure-p1.pressure)*t).coerceIn(.08f,1f)
                )
            }
        }
        output += samples.last()
        return output
    }

    /** Builds a continuous pressure-sensitive nib outline, including smooth round end caps. */
    fun outline(samples: List<InkSample>, baseWidth: Float): Path {
        val path = Path()
        if (samples.isEmpty()) return path
        if (samples.size == 1) {
            val radius = width(baseWidth, samples[0].pressure, 0, 1) / 2f
            path.addOval(androidx.compose.ui.geometry.Rect(samples[0].position.x-radius, samples[0].position.y-radius, samples[0].position.x+radius, samples[0].position.y+radius))
            return path
        }
        val left = ArrayList<Offset>(samples.size)
        val right = ArrayList<Offset>(samples.size)
        val radii = FloatArray(samples.size)
        val normals = ArrayList<Offset>(samples.size)
        for (i in samples.indices) {
            val before = samples[(i-1).coerceAtLeast(0)].position
            val after = samples[(i+1).coerceAtMost(samples.lastIndex)].position
            val dx = after.x-before.x
            val dy = after.y-before.y
            val len = sqrt(dx*dx+dy*dy).coerceAtLeast(.001f)
            val normal = Offset(-dy/len,dx/len)
            normals += normal
            val taper = min(((i+1)/2.5f).coerceIn(.70f,1f),((samples.size-i)/2.5f).coerceIn(.70f,1f))
            val radius = width(baseWidth,samples[i].pressure,i,samples.size)*taper/2f
            radii[i]=radius
            left += samples[i].position + normal*radius
            right += samples[i].position - normal*radius
        }
        path.moveTo(left.first().x,left.first().y)
        left.drop(1).forEach { path.lineTo(it.x,it.y) }
        // Round terminal cap, sampled as a small semicircle rather than a sharp polygon corner.
        val end=samples.last().position;val endN=normals.last();val endAngle=atan2(endN.y,endN.x);val endR=radii.last()
        for(k in 1..6){val angle=endAngle-PI.toFloat()*k/6f;path.lineTo(end.x+cos(angle)*endR,end.y+sin(angle)*endR)}
        for(i in right.lastIndex-1 downTo 0)path.lineTo(right[i].x,right[i].y)
        val start=samples.first().position;val startN=normals.first();val startAngle=atan2(startN.y,startN.x);val startR=radii.first()
        for(k in 1..6){val angle=startAngle-PI.toFloat()+PI.toFloat()*k/6f;path.lineTo(start.x+cos(angle)*startR,start.y+sin(angle)*startR)}
        path.close()
        return path
    }

    /** Pressure is intentionally nonlinear and continuous; raw sensor spikes are filtered upstream. */
    fun width(base: Float, pressure: Float, sampleIndex: Int, sampleCount: Int): Float {
        val p=pressure.coerceIn(.08f,1f)
        val taper=min(((sampleIndex+1)/2.5f).coerceIn(.78f,1f),((sampleCount-sampleIndex)/2.5f).coerceIn(.78f,1f))
        return (base*(.54f+.94f*p)*taper).coerceAtLeast(.65f)
    }

    /** Velocity-adaptive stabilizer: quietens slow jitter while opening up at writing speed. */
    fun stabilize(previous: Offset, incoming: Offset, amount: Float, speedPxPerMs: Float = 0f): Offset {
        val lag=amount.coerceIn(0f,.88f)/(1f+speedPxPerMs.coerceAtLeast(0f)*.35f)
        val follow=(1f-lag).coerceIn(.12f,1f)
        return Offset(previous.x+(incoming.x-previous.x)*follow,previous.y+(incoming.y-previous.y)*follow)
    }
}
