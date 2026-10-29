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
    private val holdDimLines = FloatArray(scene.holdsDim.size)

    fun setTheme(t: ScopeTheme) { theme = t }

    /** World-space layers (geometry only, no text). Recorded into an offscreen layer by [ScopeView] and re-used while panning/zooming. */
    fun drawStatic(c: Canvas, cx: Float, cy: Float, z: Float, ax: Float, ay: Float, w: Float, h: Float) {
        val t = theme; val d = density
        val inv = 1f / z
        c.save()
        c.translate(ax, ay); c.scale(z, -z); c.translate(-cx, -cy)

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

        for (i in scene.runwayPaths.indices) {
            fill.color = t.runwayFill; c.drawPath(scene.runwayPaths[i], fill)
            stroke.color = t.runwayEdge; stroke.strokeWidth = t.runwayEdgeDp * d * inv; c.drawPath(scene.runwayPaths[i], stroke)
            dashPaint.color = t.runwayMark; dashPaint.strokeWidth = maxOf(1f * d * inv, 1.2f)
            c.drawPath(scene.runwayCenterPaths[i], dashPaint)
        }

        // hand-off zones: dashed extended centrelines
        dashPaint.color = (t.runwayMark and 0x00FFFFFF) or 0x99000000.toInt(); dashPaint.strokeWidth = maxOf(1f * d * inv, 1.2f)
        c.drawPath(scene.approachPath, dashPaint)

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
        c.drawLines(holdLines, stroke)
        val hd = scene.holdsDim
        o = 0
        while (o < hd.size) {
            holdDimLines[o] = hd[o] - hd[o + 2] * hl; holdDimLines[o + 1] = hd[o + 1] - hd[o + 3] * hl
            holdDimLines[o + 2] = hd[o] + hd[o + 2] * hl; holdDimLines[o + 3] = hd[o + 1] + hd[o + 3] * hl
            o += 4
        }
        stroke.color = (t.hold and 0x00FFFFFF) or 0x55000000
        c.drawLines(holdDimLines, stroke); stroke.strokeCap = Paint.Cap.ROUND

        // stand dots
        if (z / d >= 0.15f) {
            stroke.color = t.stand; stroke.strokeWidth = t.standStrokeDp * d * inv
            val r = (if (z / d >= 0.5f) 3f else 1.8f) * d * inv
            val sx = scene.standXY
            val minX = cx - ax * inv - r; val maxX = cx + (w - ax) * inv + r
            val minY = cy - (h - ay) * inv - r; val maxY = cy + ay * inv + r
            var i = 0
            while (i < sx.size) {
                val x = sx[i]; val y = sx[i + 1]
                if (x in minX..maxX && y in minY..maxY) c.drawCircle(x, y, r, stroke)
                i += 2
            }
        }
        c.restore()
    }

    // placed label boxes this frame (l, t, r, b), for collision avoidance
    private val placed = FloatArray(4 * 512); private var nPlaced = 0
    private fun free(l: Float, t: Float, r: Float, b: Float): Boolean {
        var i = 0
        while (i < nPlaced) { if (l < placed[i + 2] && r > placed[i] && t < placed[i + 3] && b > placed[i + 1]) return false; i += 4 }
        return true
    }
    private fun claim(l: Float, t: Float, r: Float, b: Float) {
        if (nPlaced + 4 > placed.size) return
        placed[nPlaced] = l; placed[nPlaced + 1] = t; placed[nPlaced + 2] = r; placed[nPlaced + 3] = b; nPlaced += 4
    }

    /** Screen-space text pass with LOD. Taxiway refs claim space first; stand refs fill what is left. */
    fun drawLabels(c: Canvas, cam: Camera) {
        val z = cam.zoom; val w = cam.viewW; val h = cam.viewH; val t = theme; val d = density
        val level = z / d
        nPlaced = 0
        if (level >= 0.25f) {
            text.textSize = 10.5f * d; text.color = t.label; labelBg.color = t.labelBg
            val tl = scene.taxiLabels
            for (k in tl.indices) {
                val l = tl[k]
                val px = (l.x - cam.cx) * z + cam.ax; val py = cam.ay - (l.y - cam.cy) * z
                if (px < 12 || px > w - 12 || py < 12 || py > h - 12) continue
                val half = (l.text.length * 6.4f + 5f) * d / 2
                c.drawRect(px - half, py - 7.5f * d, px + half, py + 7.5f * d, labelBg)
                c.drawText(l.text, px, py + 4f * d, text)
                claim(px - half, py - 7.5f * d, px + half, py + 7.5f * d)
            }
        }
        // runway designators claim space too
        rwyText.color = t.runwayEdge; rwyText.textSize = 15f * d
        val r = scene.rwyLabelXY
        var k = 0
        while (k < r.size) {
            val px = (r[k] - cam.cx) * z + cam.ax + r[k + 2] * 24f * d
            val py = cam.ay - (r[k + 1] - cam.cy) * z - r[k + 3] * 24f * d
            c.drawText(scene.rwyLabelText[k / 4], px, py + 5f * d, rwyText)
            claim(px - 14f * d, py - 8f * d, px + 14f * d, py + 8f * d)
            k += 4
        }
        // stand refs once typical neighbours are >= 14 px apart on screen
        if (scene.standSpacing * z >= 14f) {
            val fs = 9f * d
            text.textSize = fs; text.color = t.standText
            val cw = fs * 0.6f
            val sx = scene.standXY
            var i = 0
            while (i < sx.size) {
                val ref = scene.standRefs[i / 2]
                if (ref != null) {
                    val px = (sx[i] - cam.cx) * z + cam.ax; val py = cam.ay - (sx[i + 1] - cam.cy) * z - 6f * d
                    if (px > -20 && px < w + 20 && py > -20 && py < h + 20) {
                        val hw = ref.length * cw / 2 + 1f * d
                        if (free(px - hw, py - fs, px + hw, py + 2f * d)) { c.drawText(ref, px, py, text); claim(px - hw, py - fs, px + hw, py + 2f * d) }
                    }
                }
                i += 2
            }
        }
    }
}
