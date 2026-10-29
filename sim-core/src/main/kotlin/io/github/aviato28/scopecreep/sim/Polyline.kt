package io.github.aviato28.scopecreep.sim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Polyline in local metres (x east, y north). Headings are degrees clockwise from north. */
class Polyline(px: DoubleArray, py: DoubleArray) {
    val xs: DoubleArray; val ys: DoubleArray
    val cum: DoubleArray
    val length: Double
    /** Heading change (deg, 0..180) across a +-8 m window at each vertex; drives speed in turns. */
    val turnDeg: DoubleArray

    init {
        val ax = ArrayList<Double>(); val ay = ArrayList<Double>()
        for (i in px.indices) {
            if (ax.isNotEmpty() && hypot(px[i] - ax.last(), py[i] - ay.last()) < 0.05) continue
            ax += px[i]; ay += py[i]
        }
        if (ax.size == 1) { ax += ax[0] + 0.1; ay += ay[0] }
        xs = ax.toDoubleArray(); ys = ay.toDoubleArray()
        cum = DoubleArray(xs.size)
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        length = cum.last()
        turnDeg = DoubleArray(xs.size)
        for (i in 1 until xs.size - 1) turnDeg[i] = angleDiff(headingAt(cum[i] + 8.0), headingAt(cum[i] - 8.0))
    }

    val size get() = xs.size

    /** Index i of the segment (i, i+1) containing distance s. */
    fun seg(s: Double, hint: Int = 0): Int {
        var i = hint.coerceIn(0, xs.size - 2)
        while (i > 0 && cum[i] > s) i--
        while (i < xs.size - 2 && cum[i + 1] <= s) i++
        return i
    }

    fun xAt(s: Double): Double { val c = s.coerceIn(0.0, length); val i = seg(c); val l = cum[i + 1] - cum[i]; val t = if (l > 0) (c - cum[i]) / l else 0.0; return xs[i] + (xs[i + 1] - xs[i]) * t }
    fun yAt(s: Double): Double { val c = s.coerceIn(0.0, length); val i = seg(c); val l = cum[i + 1] - cum[i]; val t = if (l > 0) (c - cum[i]) / l else 0.0; return ys[i] + (ys[i + 1] - ys[i]) * t }
    fun headingAt(s: Double): Double { val i = seg(s.coerceIn(0.0, length)); return headingOf(xs[i + 1] - xs[i], ys[i + 1] - ys[i]) }

    /** Distance along the polyline of the point closest to (x, y). */
    fun project(x: Double, y: Double): Double {
        var best = Double.MAX_VALUE; var bs = 0.0
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]; val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((x - xs[i]) * dx + (y - ys[i]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(x - (xs[i] + dx * t), y - (ys[i] + dy * t))
            if (d < best) { best = d; bs = cum[i] + t * (cum[i + 1] - cum[i]) }
        }
        return bs
    }

    /** Part of this polyline from distance s to the end. */
    fun from(s: Double): Polyline {
        val c = s.coerceIn(0.0, length); val i = seg(c)
        val x = ArrayList<Double>(); val y = ArrayList<Double>()
        x += xAt(c); y += yAt(c)
        for (k in i + 1 until xs.size) { x += xs[k]; y += ys[k] }
        return Polyline(x.toDoubleArray(), y.toDoubleArray())
    }

    /** Same line with [ext] metres appended straight on from the last segment. */
    fun extended(ext: Double): Polyline {
        val h = Math.toRadians(headingAt(length))
        return Polyline(xs + (xs.last() + Math.sin(h) * ext), ys + (ys.last() + Math.cos(h) * ext))
    }

    companion object {
        fun headingOf(dx: Double, dy: Double): Double { val h = Math.toDegrees(atan2(dx, dy)); return if (h < 0) h + 360 else h }
        fun angleDiff(a: Double, b: Double): Double { var d = abs(a - b) % 360; if (d > 180) d = 360 - d; return d }
        fun flat(p: DoubleArray) = Polyline(DoubleArray(p.size / 2) { p[it * 2] }, DoubleArray(p.size / 2) { p[it * 2 + 1] })
    }
}
