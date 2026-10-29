package io.github.aviato28.scopecreep.scope

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** World is metres, x east / y north. zoom = screen px per metre. Mutating bumps [version] to redraw. */
class Camera(private val bounds: FloatArray /* minX, minY, maxX, maxY */) {
    var cx = (bounds[0] + bounds[2]) / 2; private set
    var cy = (bounds[1] + bounds[3]) / 2; private set
    var zoom = 0.1f; private set
    var viewW = 0f; private set
    var viewH = 0f; private set
    var fitZoom = 0.1f; private set
    var maxZoom = 3f; private set
    var version by mutableIntStateOf(0); private set
    private var sized = false

    fun setViewSize(w: Float, h: Float, density: Float) {
        if (w <= 0f || h <= 0f || (w == viewW && h == viewH)) return
        viewW = w; viewH = h
        fitZoom = minOf(w / (bounds[2] - bounds[0]), h / (bounds[3] - bounds[1]))
        maxZoom = 3.5f * density
        if (!sized) { zoom = fitZoom; sized = true }
        clamp(); version++
    }

    fun panBy(dxPx: Float, dyPx: Float) { cx -= dxPx / zoom; cy += dyPx / zoom; clamp(); version++ }

    /** Zoom by [factor] keeping the world point under screen (px,py) fixed. */
    fun zoomAbout(px: Float, py: Float, factor: Float) {
        val nz = (zoom * factor).coerceIn(fitZoom, maxZoom)
        val wx = cx + (px - viewW / 2) / zoom
        val wy = cy - (py - viewH / 2) / zoom
        zoom = nz
        cx = wx - (px - viewW / 2) / nz
        cy = wy + (py - viewH / 2) / nz
        clamp(); version++
    }

    fun setZoomAbout(px: Float, py: Float, target: Float) = zoomAbout(px, py, target / zoom)

    private fun clamp() {
        val hw = viewW / zoom / 2; val hh = viewH / zoom / 2
        cx = if (hw * 2 >= bounds[2] - bounds[0]) (bounds[0] + bounds[2]) / 2 else cx.coerceIn(bounds[0] + hw, bounds[2] - hw)
        cy = if (hh * 2 >= bounds[3] - bounds[1]) (bounds[1] + bounds[3]) / 2 else cy.coerceIn(bounds[1] + hh, bounds[3] - hh)
    }
}
