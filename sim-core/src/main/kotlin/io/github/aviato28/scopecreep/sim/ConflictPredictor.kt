package io.github.aviato28.scopecreep.sim

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Conflict prediction (design.md section 6, batch 2): projects every moving aircraft along its assigned route for
 * [SimConfig.predictHorizonS] seconds with the same speed model the simulation uses, and reports
 *  - HEAD_ON: two aircraft nose to nose on one line closer than [SimConfig.predictHeadOnM];
 *  - JUNCTION: two aircraft reaching one node from different edges within [SimConfig.predictJunctionS];
 *  - RUNWAY: a landing aircraft that would arrive while something (lined up, rolling, crossing, stopped, closed) still holds the runway.
 * A follower in the same direction is never reported (it just stops behind). Refreshed by [Simulation] every
 * [SimConfig.predictEveryS]; all buffers are preallocated, so the draw code can read [count], [kind], [a], [b], [etaS] freely.
 */
class ConflictPredictor internal constructor(private val sim: Simulation) {
    companion object {
        const val HEAD_ON = 0; const val JUNCTION = 1; const val RUNWAY = 2
        const val MAX = 8; private const val MAXA = 32; private const val MAXN = 24
        /** "CONFLICT 0:25" for 0..99 s, prebuilt so drawing never formats strings. */
        val LABELS = Array(100) { "CONFLICT %d:%02d".format(it / 60, it % 60) }
        fun label(sec: Double) = LABELS[sec.toInt().coerceIn(0, 99)]
    }

    private val cfg = sim.config
    private val horizon = max(1, cfg.predict.predictHorizonS.toInt())

    var count = 0; private set
    /** Simulation time of the last refresh; the shown countdown is [etaS] minus the time since. */
    var stamp = 0.0; private set
    val kind = IntArray(MAX)
    val a = arrayOfNulls<Aircraft>(MAX)
    /** Second aircraft, or null when the other party is a point ([bx], [by]: a closed runway's vehicle). */
    val b = arrayOfNulls<Aircraft>(MAX)
    val bx = DoubleArray(MAX); val by = DoubleArray(MAX)
    /** Predicted seconds until the conflict. */
    val etaS = DoubleArray(MAX)

    // scratch
    private val mv = arrayOfNulls<Aircraft>(MAXA)
    private var nm = 0
    private val px = Array(MAXA) { DoubleArray(horizon + 1) }
    private val py = Array(MAXA) { DoubleArray(horizon + 1) }
    private val ph = Array(MAXA) { DoubleArray(horizon + 1) }
    private val sAt = Array(MAXA) { DoubleArray(horizon + 1) }
    private val jNode = Array(MAXA) { IntArray(MAXN) }
    private val jEdge = Array(MAXA) { IntArray(MAXN) }
    private val jT = Array(MAXA) { DoubleArray(MAXN) }
    private val jN = IntArray(MAXA)
    private val pt = DoubleArray(2)
    private var lx = 0.0; private var ly = 0.0; private var lh = 0.0

    private fun moving(x: Aircraft) = (x.state == AcState.TAXIING || x.state == AcState.VACATING) && !x.pathDone

    fun update() {
        for (x in sim.aircraft) x.predicted = false
        count = 0; stamp = sim.time
        nm = 0
        for (x in sim.aircraft) if (moving(x) && nm < MAXA) { mv[nm] = x; project(nm, x); nm++ }
        // moving pairs
        for (i in 0 until nm) {
            val ai = mv[i]!!
            for (j in i + 1 until nm) {
                val aj = mv[j]!!
                if (ai.conflictWith == aj.id) continue
                headOn(ai, aj, px[i], py[i], ph[i], px[j], py[j], ph[j])
                junction(i, j, ai, aj)
            }
            // moving versus standing aircraft facing it
            for (st in sim.aircraft) {
                if (st === ai || moving(st) || !standing(st) || ai.conflictWith == st.id) continue
                headOnStatic(i, ai, st)
            }
        }
        runwayConflicts()
    }

    private fun standing(x: Aircraft) = when (x.state) {
        AcState.PUSHING, AcState.REQUEST_TAXI, AcState.HOLDING_SHORT, AcState.HOLDING_XING, AcState.VACATED -> true
        AcState.TAXIING -> x.held || x.pathDone
        else -> false
    }

    /** Position and heading at path distance [s] without allocating. */
    private fun locate(p: Polyline, s: Double, hint: Int): Int {
        val c = if (s < 0.0) 0.0 else if (s > p.length) p.length else s
        val i = p.seg(c, hint)
        val l = p.cum[i + 1] - p.cum[i]
        val t = if (l > 0) (c - p.cum[i]) / l else 0.0
        lx = p.xs[i] + (p.xs[i + 1] - p.xs[i]) * t; ly = p.ys[i] + (p.ys[i + 1] - p.ys[i]) * t
        lh = Polyline.headingOf(p.xs[i + 1] - p.xs[i], p.ys[i + 1] - p.ys[i])
        return i
    }

    /** Fills [sAt], positions and the node arrival times for mover slot [k] (one-second steps, the simulation's speed model). */
    private fun project(k: Int, x: Aircraft) {
        val p = x.path
        var s = x.s; var v = x.speed
        var stopS = Double.POSITIVE_INFINITY
        if (x.state == AcState.TAXIING && x.xingDone < x.xingS.size && !x.crossing) stopS = x.xingS[x.xingDone] - cfg.crossHoldM
        val cap = if (x.held) 0.0 else cfg.taxiSpeed
        var hint = p.seg(s)
        hint = locate(p, s, hint); px[k][0] = lx; py[k][0] = ly; ph[k][0] = lh; sAt[k][0] = s
        for (t in 1..horizon) {
            var target = cap
            if (cap > 0.0) {
                var j = p.seg(s, hint) + 1
                while (j < p.size && p.cum[j] - s < 40.0) {
                    val lim = sim.turnLimit(p.turnDeg[j]); val d = p.cum[j] - s
                    target = min(target, sqrt(lim * lim + 2 * cfg.decel * d)); j++
                }
                target = min(target, max(0.5, sqrt(2 * cfg.decel * (p.length - s))))
                if (stopS < Double.POSITIVE_INFINITY) target = min(target, max(0.4, sqrt(2 * cfg.decel * max(0.0, stopS - s - 0.5))))
            }
            v = if (target < v) max(target, v - cfg.decel) else min(target, v + cfg.accel)
            s += v
            if (stopS < Double.POSITIVE_INFINITY && s > stopS) s = max(x.s, stopS)
            if (s >= p.length) { s = p.length; v = 0.0 }
            hint = locate(p, s, hint)
            px[k][t] = lx; py[k][t] = ly; ph[k][t] = lh; sAt[k][t] = s
        }
        // node arrival times (route aircraft only)
        var n = 0
        val r = x.route
        if (r != null && x.state == AcState.TAXIING && x.nodeS.isNotEmpty()) {
            var t = 1
            for (idx in 1 until x.nodeS.size - 1) {
                if (idx >= r.nodes.size) break
                val ns = x.nodeS[idx]
                if (ns <= x.s + 1.0) continue
                while (t <= horizon && sAt[k][t] < ns) t++
                if (t > horizon || n >= MAXN) break
                jNode[k][n] = r.nodes[idx]; jEdge[k][n] = r.edges[idx - 1]; jT[k][n] = t.toDouble(); n++
            }
        }
        jN[k] = n
    }

    private fun add(kd: Int, p: Aircraft, q: Aircraft?, t: Double, qx: Double = 0.0, qy: Double = 0.0): Boolean {
        for (i in 0 until count) if (a[i] === p && b[i] === q && kind[i] == kd) return false
        if (count >= MAX) return false
        kind[count] = kd; a[count] = p; b[count] = q; etaS[count] = t; bx[count] = qx; by[count] = qy; count++
        p.predicted = true; q?.predicted = true
        return true
    }

    private fun headOn(p: Aircraft, q: Aircraft, ax: DoubleArray, ay: DoubleArray, ah: DoubleArray, bx: DoubleArray, by: DoubleArray, bh: DoubleArray) {
        for (t in 0..horizon) {
            val dx = bx[t] - ax[t]; val dy = by[t] - ay[t]
            if (abs(dx) > cfg.predict.predictHeadOnM || abs(dy) > cfg.predict.predictHeadOnM) continue
            if (Polyline.angleDiff(ah[t], bh[t]) <= 130.0) continue
            val r = Math.toRadians(ah[t])
            val along = dx * Math.sin(r) + dy * Math.cos(r)
            val cross = abs(dx * Math.cos(r) - dy * Math.sin(r))
            if (along > 0.0 && along < cfg.predict.predictHeadOnM && cross < cfg.lateralM + 6.0) { add(HEAD_ON, p, q, t.toDouble()); return }
        }
    }

    /** A moving aircraft closing on one that stands still facing it (held, or stopped at a hold). */
    private fun headOnStatic(i: Int, p: Aircraft, q: Aircraft) {
        if (Polyline.angleDiff(p.heading, q.heading) <= 130.0 && Polyline.angleDiff(ph[i][horizon], q.heading) <= 130.0) return
        for (t in 0..horizon) {
            val dx = q.x - px[i][t]; val dy = q.y - py[i][t]
            if (abs(dx) > cfg.predict.predictHeadOnM || abs(dy) > cfg.predict.predictHeadOnM) continue
            if (Polyline.angleDiff(ph[i][t], q.heading) <= 130.0) continue
            val r = Math.toRadians(ph[i][t])
            val along = dx * Math.sin(r) + dy * Math.cos(r)
            val cross = abs(dx * Math.cos(r) - dy * Math.sin(r))
            if (along > 0.0 && along < cfg.predict.predictHeadOnM && cross < cfg.lateralM + 6.0) { add(HEAD_ON, p, q, t.toDouble()); return }
        }
    }

    private fun junction(i: Int, j: Int, p: Aircraft, q: Aircraft) {
        for (m in 0 until jN[i]) for (n in 0 until jN[j]) {
            if (jNode[i][m] != jNode[j][n] || jEdge[i][m] == jEdge[j][n]) continue
            if (abs(jT[i][m] - jT[j][n]) > cfg.predict.predictJunctionS) continue
            add(JUNCTION, p, q, min(jT[i][m], jT[j][n])); return
        }
    }

    private fun runwayConflicts() {
        for (arr in sim.aircraft) {
            if (arr.state != AcState.FINAL) continue
            val eta = sim.etaS(arr)
            if (eta > cfg.predict.predictHorizonS || eta < 0.0) continue
            val rwId = sim.rid(arr.rwyEnd)
            if (sim.ops.blockerPoint(rwId, pt)) { add(RUNWAY, arr, null, eta, pt[0], pt[1]); continue }
            for (o in sim.aircraft) {
                if (o === arr || !sim.occupies(o, rwId, true)) continue
                if (sim.clearsIn(o) <= eta + 5.0) continue
                add(RUNWAY, arr, o, eta); break
            }
        }
    }
}
