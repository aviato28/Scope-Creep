package io.github.aviato28.scopecreep.scope

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.game.Mode
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
        // ---- world space: route previews
        c.save(); c.translate(cam.ax, cam.ay); c.scale(z, -z); c.translate(-cam.cx, -cam.cy)
        if (g.hasDim1) { dash.color = t.routeDim; dash.strokeWidth = 3f * d * inv; c.drawPath(g.dimPath1, dash) }
        if (g.hasDim2) { dash.color = t.routeDim; dash.strokeWidth = 3f * d * inv; c.drawPath(g.dimPath2, dash) }
        if (g.hasSel) { line.color = t.selected; line.strokeWidth = 4f * d * inv; c.drawPath(g.selPath, line) }
        if (g.hasBuild) { line.color = t.selected; line.strokeWidth = 4f * d * inv; c.drawPath(g.buildPath, line) }
        c.restore()

        // ---- hold pulses while choosing a destination
        val sim = g.sim
        if (g.mode == Mode.DEST) {
            val ph = (g.timeSec * 1.6f) % 1f
            line.color = t.departure; line.strokeWidth = 2f * d
            fill.color = (t.departure and 0x00FFFFFF) or 0x40000000
            for (dst in g.destList) {
                val px = (dst.x.toFloat() - cam.cx) * z + cam.ax; val py = cam.ay - (dst.y.toFloat() - cam.cy) * z
                if (px < -40 || px > w + 40 || py < -40 || py > h + 40) continue
                c.drawCircle(px, py, (9f + 9f * ph) * d, line)
                c.drawCircle(px, py, 5f * d, fill)
                txt.color = t.departure; txt.textSize = 11f * d; txt.textAlign = Paint.Align.LEFT
                c.drawText(dst.label, px + 12f * d, py - 10f * d, txt)
            }
        }

        // ---- aircraft (constant screen size); data blocks nudged apart in screen space
        val al = sim.alpha
        nb = 0
        txt.textSize = 10.5f * d; txt.textAlign = Paint.Align.LEFT
        val lh = 12.5f * d; val bh = lh * 3.1f; val gapPx = 2f * d
        for (a in sim.aircraft) {
            val px = (a.interpX(al).toFloat() - cam.cx) * z + cam.ax; val py = cam.ay - (a.interpY(al).toFloat() - cam.cy) * z
            if (px < -80 || px > w + 80 || py < -80 || py > h + 80) continue
            val sel = a.id == g.selectedId
            val conflict = a.conflictWith != 0
            val col = if (conflict) t.warn else if (sel) t.selected else t.departure
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

    private companion object { const val SLOTS = 6 }
}
