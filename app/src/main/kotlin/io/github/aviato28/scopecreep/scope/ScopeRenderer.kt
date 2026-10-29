package io.github.aviato28.scopecreep.scope

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.aviato28.scopecreep.theme.ScopeTheme

/**
 * Draws a [ScopeScene] with preallocated paints; draw() performs no allocations.
 * LOD (zoom is px per metre; thresholds in dp per metre via [density]):
 *  runway designators + hold bars always; taxiway refs >= 0.25 dp/m; stand dots >= 0.15 dp/m; stand refs >= 1.0 dp/m;
 *  taxiway bands (real width) once the band is >= 3 px wide.
 */
class ScopeRenderer(private val scene: ScopeScene, private var theme: ScopeTheme, private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(30f, 30f), 0f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val rwyText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val labelBg = Paint().apply { style = Paint.Style.FILL }
    private val holdLines = FloatArray(scene.holds.size)

    fun setTheme(t: ScopeTheme) { theme = t }

    fun draw(c: Canvas, cam: Camera) {
        val z = cam.zoom; val w = cam.viewW; val h = cam.viewH; val t = theme; val d = density
        val inv = 1f / z
        c.drawColor(t.bg)
        c.save()
        c.translate(w / 2, h / 2); c.scale(z, -z); c.translate(-cam.cx, -cam.cy)

        fill.color = t.apronFill; c.drawPath(scene.apronPath, fill)
        stroke.color = t.apronStroke; stroke.strokeWidth = d * inv; c.drawPath(scene.apronPath, stroke)
        fill.color = t.buildingFill; c.drawPath(scene.buildingPath, fill)
        stroke.color = t.buildingStroke; c.drawPath(scene.buildingPath, stroke)

        // taxiway bands at real width, only once they are visible as areas
        stroke.color = t.taxiBand
        if (23f * z >= 3f) { stroke.strokeWidth = 23f; c.drawPath(scene.taxiPath, stroke) }
        if (35f * z >= 3f) { stroke.strokeWidth = 35f; c.drawPath(scene.widePath, stroke) }
        // centrelines stroked by width category
        stroke.color = t.laneLine; stroke.strokeWidth = t.laneStrokeDp * d * inv; c.drawPath(scene.lanePath, stroke)
        stroke.color = t.taxiLine; stroke.strokeWidth = t.taxiStrokeDp * d * inv; c.drawPath(scene.taxiPath, stroke)
        stroke.strokeWidth = t.wideStrokeDp * d * inv; c.drawPath(scene.widePath, stroke)

        // runways
        for (i in scene.runwayPaths.indices) {
            fill.color = t.runwayFill; c.drawPath(scene.runwayPaths[i], fill)
            stroke.color = t.runwayEdge; stroke.strokeWidth = t.runwayEdgeDp * d * inv; c.drawPath(scene.runwayPaths[i], stroke)
            dashPaint.color = t.runwayMark; dashPaint.strokeWidth = maxOf(1f * d * inv, 1.2f)
            c.drawPath(scene.runwayCenterPaths[i], dashPaint)
        }

        // hold-short bars (fixed pixel length)
        val hl = 9f * d * inv
        val hs = scene.holds
        var o = 0
        while (o < hs.size) {
            holdLines[o] = hs[o] - hs[o + 2] * hl; holdLines[o + 1] = hs[o + 1] - hs[o + 3] * hl
            holdLines[o + 2] = hs[o] + hs[o + 2] * hl; holdLines[o + 3] = hs[o + 1] + hs[o + 3] * hl
            o += 4
        }
        stroke.color = t.hold; stroke.strokeWidth = t.holdStrokeDp * d * inv; stroke.strokeCap = Paint.Cap.BUTT
        c.drawLines(holdLines, stroke); stroke.strokeCap = Paint.Cap.ROUND

        // stands
        val level = z / d
        if (level >= 0.15f) {
            stroke.color = t.stand; stroke.strokeWidth = t.standStrokeDp * d * inv
            val r = (if (level >= 0.5f) 3f else 1.8f) * d * inv
            val sx = scene.standXY
            val minX = cam.cx - w / 2 * inv - r; val maxX = cam.cx + w / 2 * inv + r
            val minY = cam.cy - h / 2 * inv - r; val maxY = cam.cy + h / 2 * inv + r
            var i = 0
            while (i < sx.size) {
                val x = sx[i]; val y = sx[i + 1]
                if (x in minX..maxX && y in minY..maxY) c.drawCircle(x, y, r, stroke)
                i += 2
            }
        }
        c.restore()

        // ---- screen-space text
        val showStandRefs = level >= 1.0f
        if (showStandRefs) {
            text.color = t.standText; text.textSize = 9f * d
            val sx = scene.standXY
            var i = 0
            while (i < sx.size) {
                val ref = scene.standRefs[i / 2]
                if (ref != null) {
                    val px = (sx[i] - cam.cx) * z + w / 2; val py = h / 2 - (sx[i + 1] - cam.cy) * z
                    if (px > -20 && px < w + 20 && py > -20 && py < h + 20) c.drawText(ref, px, py - 6f * d, text)
                }
                i += 2
            }
        }
        if (level >= 0.25f) {
            text.textSize = 10.5f * d; text.color = t.label; labelBg.color = t.labelBg
            val tl = scene.taxiLabels
            for (k in tl.indices) {
                val l = tl[k]
                val px = (l.x - cam.cx) * z + w / 2; val py = h / 2 - (l.y - cam.cy) * z
                if (px < 12 || px > w - 12 || py < 12 || py > h - 12) continue
                val half = (l.text.length * 6.4f + 5f) * d / 2
                c.drawRect(px - half, py - 7.5f * d, px + half, py + 7.5f * d, labelBg)
                c.drawText(l.text, px, py + 4f * d, text)
            }
        }
        rwyText.color = t.runwayEdge; rwyText.textSize = 15f * d
        val r = scene.rwyLabelXY
        var k = 0
        while (k < r.size) {
            val px = (r[k] - cam.cx) * z + w / 2 + r[k + 2] * 24f * d
            val py = h / 2 - (r[k + 1] - cam.cy) * z - r[k + 3] * 24f * d
            c.drawText(scene.rwyLabelText[k / 4], px, py + 5f * d, rwyText)
            k += 4
        }
    }
}
