package io.github.aviato28.scopecreep.scope

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.game.Mode
import io.github.aviato28.scopecreep.sim.AcState
import io.github.aviato28.scopecreep.theme.ScopeTheme
import kotlin.math.sin

/** Aircraft, data blocks, route previews and hold pulses. Preallocated paints/paths; draw() does not allocate. */
class GameOverlay(private val density: Float, private var theme: ScopeTheme) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val bg = Paint().apply { style = Paint.Style.FILL }
    private val chevron = Path().apply { moveTo(0f, -1f); lineTo(0.75f, 0.8f); lineTo(0f, 0.4f); lineTo(-0.75f, 0.8f); close() }

    // placed data blocks this frame (screen space) and each aircraft's last slot; no per-frame allocation
    private val ox = FloatArray(48); private val oy = FloatArray(48); private val ow = FloatArray(48)
    private var nb = 0
    private val slotOf = IntArray(64)

    fun setTheme(t: ScopeTheme) { theme = t }

    fun draw(c: Canvas, cam: Camera, g: GameController) {
        val z = cam.zoom; val w = cam.viewW; val h = cam.viewH; val d = density; val t = theme
        val inv = 1f / z
        // ---- world space: other active routes (faint, dashed), the selected aircraft's remaining route, the plan
        c.save(); c.translate(cam.ax, cam.ay); c.scale(z, -z); c.translate(-cam.cx, -cam.cy)
        for (i in 0 until g.otherCount) { dash.color = t.routeDim; dash.strokeWidth = 2.5f * d * inv; c.drawPath(g.otherPaths[i], dash) }
        val sa = g.selected
        if (sa != null && g.mode != Mode.PLAN && showsRoute(sa)) {
            GameController.fillRemaining(remPath, sa.path, sa.s)
            line.color = (t.selected and 0x00FFFFFF) or 0xB0000000.toInt(); line.strokeWidth = 3f * d * inv; c.drawPath(remPath, line)
        }
        if (g.hasGhost && g.timeSec < g.ghostUntil) { dash.color = (t.selected and 0x00FFFFFF) or 0x70000000; dash.strokeWidth = 4f * d * inv; c.drawPath(g.ghostPath, dash) }
        if (g.hasAvoid) { dash.color = t.warn; dash.strokeWidth = 5f * d * inv; c.drawPath(g.avoidPath, dash) }
        if (g.hasVia) { dash.color = t.departure; dash.strokeWidth = 5f * d * inv; c.drawPath(g.viaPath, dash) }
        if (g.hasSel && g.mode == Mode.PLAN) { line.color = t.selected; line.strokeWidth = 4.5f * d * inv; c.drawPath(g.selPath, line) }
        c.restore()

        // ---- screen space: taxiway tags on the planned route, via tags, avoid crosses
        if (g.mode == Mode.PLAN) {
            txt.textSize = 10f * d; txt.textAlign = Paint.Align.CENTER
            for (i in 0 until g.refCount) {
                val tx = (g.refX[i] - cam.cx) * z + cam.ax; val ty = cam.ay - (g.refY[i] - cam.cy) * z
                if (tx < -40 || tx > w + 40 || ty < -40 || ty > h + 40) continue
                val s = g.refText[i] ?: continue
                val hw = txt.measureText(s) / 2 + 4f * d
                bg.color = t.selected; c.drawRect(tx - hw, ty - 9f * d, tx + hw, ty + 9f * d, bg)
                txt.color = t.bg; c.drawText(s, tx, ty + 3.6f * d, txt)
            }
            for (i in 0 until g.viaCount) {
                val tx = (g.viaX[i] - cam.cx) * z + cam.ax; val ty = cam.ay - (g.viaY[i] - cam.cy) * z
                bg.color = t.departure; c.drawRect(tx - 16f * d, ty + 10f * d, tx + 16f * d, ty + 26f * d, bg)
                txt.color = t.bg; c.drawText("VIA", tx, ty + 22f * d, txt)
            }
            line.color = t.warn; line.strokeWidth = 3f * d
            for (i in 0 until g.avoidCount) {
                val tx = (g.avoidX[i] - cam.cx) * z + cam.ax; val ty = cam.ay - (g.avoidY[i] - cam.cy) * z
                val r = 7f * d
                bg.color = t.bg; c.drawCircle(tx, ty, r + 3f * d, bg)
                c.drawLine(tx - r, ty - r, tx + r, ty + r, line); c.drawLine(tx - r, ty + r, tx + r, ty - r, line)
            }
            txt.textAlign = Paint.Align.LEFT
        }

        // ---- hold pulses while choosing a destination
        val sim = g.sim
        if (g.mode == Mode.DEST || g.mode == Mode.PLAN) {
            val ph = (g.timeSec * 1.6f) % 1f
            line.color = t.departure; line.strokeWidth = 2f * d
            fill.color = (t.departure and 0x00FFFFFF) or 0x40000000
            val plan = g.mode == Mode.PLAN
            val nd = if (plan) 1 else g.destList.size
            for (k in 0 until nd) {
                val dst = (if (plan) g.dest else g.destList[k]) ?: continue
                val px = (dst.x.toFloat() - cam.cx) * z + cam.ax; val py = cam.ay - (dst.y.toFloat() - cam.cy) * z
                if (px < -40 || px > w + 40 || py < -40 || py > h + 40) continue
                c.drawCircle(px, py, (9f + 9f * ph) * d, line)
                c.drawCircle(px, py, 5f * d, fill)
                txt.color = t.departure; txt.textSize = 11f * d; txt.textAlign = Paint.Align.LEFT
                c.drawText(dst.label, px + 12f * d, py - 10f * d, txt)
            }
        }

        // ---- follow-me vehicles: small yellow chevrons
        val fms = sim.followMes
        for (i in 0 until fms.size) {
            val f = fms[i]
            val fx = (f.interpX(sim.alpha).toFloat() - cam.cx) * z + cam.ax; val fy = cam.ay - (f.interpY(sim.alpha).toFloat() - cam.cy) * z
            if (fx < -40 || fx > w + 40 || fy < -40 || fy > h + 40) continue
            c.save(); c.translate(fx, fy); c.rotate(f.interpHeading(sim.alpha).toFloat()); c.scale(6f * d, 6f * d)
            fill.color = FM_YELLOW; c.drawPath(chevron, fill); c.restore()
            txt.color = FM_YELLOW; txt.textSize = 9.5f * d; txt.textAlign = Paint.Align.LEFT
            c.drawText(f.name, fx + 9f * d, fy - 7f * d, txt)
        }

        // ---- aircraft (constant screen size); data blocks nudged apart in screen space
        val al = sim.alpha
        nb = 0
        txt.textSize = 10.5f * d; txt.textAlign = Paint.Align.LEFT
        val lh = 12.5f * d; val bh = lh * 3.1f; val gapPx = 2f * d
        for (a in sim.aircraft) {
            val px = (a.interpX(al).toFloat() - cam.cx) * z + cam.ax; val py = cam.ay - (a.interpY(al).toFloat() - cam.cy) * z
            val sel = a.id == g.selectedId
            val conflict = a.conflictWith != 0
            val col = if (conflict) t.warn else if (sel) t.selected else if (a.isArrival) t.arrival else t.departure
            if (px < -80 || px > w + 80 || py < -80 || py > h + 80) {
                // an arrival still out in the hand-off zone: a marker on the screen edge, pointing at it
                if (a.state == AcState.FINAL) {
                    val ex = GameController.edgeX(px, w, d); val ey = GameController.edgeY(py, h, d)
                    val ang = Math.toDegrees(Math.atan2((px - ex).toDouble(), -(py - ey).toDouble())).toFloat()
                    c.save(); c.translate(ex, ey); c.rotate(ang); c.scale(7f * d, 7f * d)
                    fill.color = col; c.drawPath(chevron, fill)
                    c.restore()
                    txt.color = col; txt.textSize = 10.5f * d; txt.textAlign = if (ex > w / 2) Paint.Align.RIGHT else Paint.Align.LEFT
                    c.drawText(a.callsign, ex + (if (ex > w / 2) -12f else 12f) * d, ey + 4f * d, txt)
                    if (sel) { line.color = col; line.strokeWidth = 1.5f * d; c.drawCircle(ex, ey, 14f * d, line) }
                    txt.textAlign = Paint.Align.LEFT
                }
                continue
            }
            val third = if (conflict) "CONFLICT" else a.instruction
            val bw = maxOf(txt.measureText(a.callsign), txt.measureText(a.typeLine), txt.measureText(third)) + 8f * d
            // pick the first free slot, trying last frame's choice first
            val pref = slotOf[a.id and 63]
            var bx = 0f; var top = 0f; var chosen = pref
            for (k in 0 until SLOTS) {
                val sl = (pref + k) % SLOTS
                val cx = if (sl == 0 || sl == 1 || sl == 4) px + 26f * d else px - 26f * d - bw
                val cy = when (sl) { 0, 2 -> py - 30f * d - lh * 0.5f; 1, 3 -> py + 16f * d; 4 -> py - 30f * d - lh * 0.5f - bh - gapPx; else -> py + 16f * d + bh + gapPx }
                var hit = false
                for (j in 0 until nb) if (cx < ox[j] + ow[j] + gapPx && cx + bw + gapPx > ox[j] && cy < oy[j] + bh + gapPx && cy + bh + gapPx > oy[j]) { hit = true; break }
                if (k == 0 || !hit) { bx = cx; top = cy; chosen = sl }
                if (!hit) break
            }
            slotOf[a.id and 63] = chosen
            if (nb < ox.size) { ox[nb] = bx; oy[nb] = top; ow[nb] = bw; nb++ }
            val right = bx > px
            line.color = col; line.strokeWidth = 1f * d
            c.drawLine(px + (if (right) 6f else -6f) * d, py - 6f * d, if (right) bx else bx + bw, top + lh * 1.55f, line)
            bg.color = t.labelBg; c.drawRect(bx, top, bx + bw, top + bh, bg)
            if (sel || conflict) { line.strokeWidth = (if (conflict) 2f else 1.5f) * d; c.drawRect(bx, top, bx + bw, top + bh, line) }
            txt.color = col
            c.drawText(a.callsign, bx + 4f * d, top + lh * 0.95f, txt)
            txt.color = if (conflict) t.warn else if (sel) t.selected else t.dim
            c.drawText(a.typeLine, bx + 4f * d, top + lh * 1.95f, txt)
            txt.color = col
            c.drawText(third, bx + 4f * d, top + lh * 2.95f, txt)
            // oriented chevron
            c.save(); c.translate(px, py); c.rotate(a.interpHeading(al).toFloat()); c.scale(8f * d, 8f * d)
            fill.color = col; c.drawPath(chevron, fill)
            c.restore()
            if (sel || conflict) { line.color = col; line.strokeWidth = 1.5f * d; c.drawCircle(px, py, 15f * d, line) }
        }
    }

    private val remPath = Path()
    private fun showsRoute(a: io.github.aviato28.scopecreep.sim.Aircraft) = when (a.state) {
        AcState.TAXIING, AcState.VACATING, AcState.ROLLOUT, AcState.TAKEOFF_ROLL -> true
        AcState.FINAL -> a.cleared
        else -> false
    }

    private companion object { const val SLOTS = 6; const val FM_YELLOW = 0xFFFFD84A.toInt() }
}
