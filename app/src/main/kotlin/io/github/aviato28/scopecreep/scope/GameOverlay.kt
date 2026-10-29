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

    fun setTheme(t: ScopeTheme) { theme = t }

    fun draw(c: Canvas, cam: Camera, g: GameController) {
        val z = cam.zoom; val w = cam.viewW; val h = cam.viewH; val d = density; val t = theme
        val inv = 1f / z
        // ---- world space: route previews
        c.save(); c.translate(w / 2, h / 2); c.scale(z, -z); c.translate(-cam.cx, -cam.cy)
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
            for (dst in sim.destinations) {
                val px = (dst.x.toFloat() - cam.cx) * z + w / 2; val py = h / 2 - (dst.y.toFloat() - cam.cy) * z
                if (px < -40 || px > w + 40 || py < -40 || py > h + 40) continue
                c.drawCircle(px, py, (9f + 9f * ph) * d, line)
                c.drawCircle(px, py, 5f * d, fill)
                txt.color = t.departure; txt.textSize = 11f * d; txt.textAlign = Paint.Align.LEFT
                c.drawText(dst.label, px + 12f * d, py - 10f * d, txt)
            }
        }

        // ---- aircraft (constant screen size)
        val al = sim.alpha
        for (a in sim.aircraft) {
            val px = (a.interpX(al).toFloat() - cam.cx) * z + w / 2; val py = h / 2 - (a.interpY(al).toFloat() - cam.cy) * z
            if (px < -80 || px > w + 80 || py < -80 || py > h + 80) continue
            val sel = a.id == g.selectedId
            val col = if (sel) t.selected else t.departure
            // leader line + data block up-right of the symbol
            val bx = px + 26f * d; val by = py - 30f * d
            txt.textSize = 10.5f * d; txt.textAlign = Paint.Align.LEFT
            val bw = maxOf(txt.measureText(a.callsign), txt.measureText(a.typeLine), txt.measureText(a.instruction)) + 8f * d
            val lh = 12.5f * d
            line.color = col; line.strokeWidth = 1f * d
            c.drawLine(px + 6f * d, py - 6f * d, bx, by + 1.5f * lh, line)
            bg.color = t.labelBg; c.drawRect(bx, by - lh * 0.5f, bx + bw, by + lh * 2.6f, bg)
            if (sel) { line.strokeWidth = 1.5f * d; c.drawRect(bx, by - lh * 0.5f, bx + bw, by + lh * 2.6f, line) }
            txt.color = col
            c.drawText(a.callsign, bx + 4f * d, by + lh * 0.45f, txt)
            txt.color = if (sel) t.selected else t.dim
            c.drawText(a.typeLine, bx + 4f * d, by + lh * 1.45f, txt)
            txt.color = col
            c.drawText(a.instruction, bx + 4f * d, by + lh * 2.45f, txt)
            // oriented chevron
            c.save(); c.translate(px, py); c.rotate(a.interpHeading(al).toFloat()); c.scale(8f * d, 8f * d)
            fill.color = col; c.drawPath(chevron, fill)
            c.restore()
            if (sel) { line.color = col; line.strokeWidth = 1.5f * d; c.drawCircle(px, py, 15f * d, line) }
        }
    }
}
