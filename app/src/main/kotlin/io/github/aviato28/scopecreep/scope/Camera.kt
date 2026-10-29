package io.github.aviato28.scopecreep.scope

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * World is metres, x east / y north. zoom = screen px per metre.
 * (cx, cy) is the world point shown at the anchor (ax, ay) = centre of the visible rectangle (the canvas minus safe
 * area and UI overlays), so the map stays put when overlays appear. Mutating bumps [version]; read it only in draw.
 */
class Camera(private val bounds: FloatArray /* minX, minY, maxX, maxY */) {
    var cx = (bounds[0] + bounds[2]) / 2; private set
    var cy = (bounds[1] + bounds[3]) / 2; private set
    var zoom = 0.1f; private set
    var viewW = 0f; private set
    var viewH = 0f; private set
    var ax = 0f; private set
    var ay = 0f; private set
    var fitZoom = 0.1f; private set
    var maxZoom = 3f; private set
    var version by mutableIntStateOf(0); private set
    private var fitted = false
    private var l = 0f; private var t = 0f; private var r = 0f; private var b = 0f

    /** [left]..[bottom] are the px reserved by insets and overlays. Fits the airport into the rest on first call only. */
    fun setViewport(w: Float, h: Float, left: Float, top: Float, right: Float, bottom: Float, density: Float) {
        if (w <= 0f || h <= 0f) return
        if (w == viewW && h == viewH && left == l && top == t && right == r && bottom == b) return
        viewW = w; viewH = h; l = left; t = top; r = right; b = bottom
        val vw = (w - left - right).coerceAtLeast(1f); val vh = (h - top - bottom).coerceAtLeast(1f)
        ax = left + vw / 2; ay = top + vh / 2
        fitZoom = minOf(vw / (bounds[2] - bounds[0]), vh / (bounds[3] - bounds[1]))
        maxZoom = maxOf(fitZoom, 48f * density / 25f)  // 25 m across 48 dp
        if (!fitted) { zoom = fitZoom; cx = (bounds[0] + bounds[2]) / 2; cy = (bounds[1] + bounds[3]) / 2; fitted = true }
        else zoom = zoom.coerceIn(fitZoom, maxZoom)
        clamp(); version++
    }

    /** One gesture step: zoom by [factor] about screen point (px,py), then pan by (dx,dy) px. Clamped every step. */
    fun transform(px: Float, py: Float, dx: Float, dy: Float, factor: Float) {
        if (viewW <= 0f) return
        if (factor != 1f) {
            val nz = (zoom * factor).coerceIn(fitZoom, maxZoom)
            val wx = cx + (px - ax) / zoom; val wy = cy - (py - ay) / zoom
            zoom = nz
            cx = wx - (px - ax) / nz; cy = wy + (py - ay) / nz
        }
        cx -= dx / zoom; cy += dy / zoom
        clamp(); version++
    }

    /** The airport edge may reach the anchor, no further. */
    private fun clamp() {
        cx = cx.coerceIn(bounds[0], bounds[2]); cy = cy.coerceIn(bounds[1], bounds[3])
    }

    companion object {
        fun saver(bounds: FloatArray) = Saver<Camera, List<Float>>(
            save = { listOf(it.cx, it.cy, it.zoom) },
            restore = { s -> Camera(bounds).apply { cx = s[0]; cy = s[1]; zoom = s[2]; fitted = true } },
        )
    }
}
