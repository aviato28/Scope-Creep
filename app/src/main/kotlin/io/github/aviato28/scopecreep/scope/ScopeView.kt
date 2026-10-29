package io.github.aviato28.scopecreep.scope

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.aviato28.scopecreep.BuildConfig
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.theme.ScopeTheme
import kotlin.math.abs
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/** Where the static layer was last recorded; the layer is re-used (and merely transformed) while the camera moves. */
private class StaticCache {
    var valid = false; var cx = 0f; var cy = 0f; var z = 1f; var w = 0; var h = 0; var mx = 0; var my = 0
    var theme: ScopeTheme? = null; var settle = 0
}

/**
 * Pan / pinch-zoom scope. The static airport is rendered once into an offscreen [GraphicsLayer] and moved with a
 * transform during gestures; it is re-recorded only when the camera drifts too far or settles. Camera state is read
 * only inside the draw lambda, so gestures and the game loop never recompose anything.
 */
@OptIn(FlowPreview::class)
@Composable
fun ScopeView(scene: ScopeScene, theme: ScopeTheme, game: GameController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val camera = rememberSaveable(scene, saver = Camera.saver(scene.bounds, scene.panBounds)) { Camera(scene.bounds, scene.panBounds) }
    val renderer = remember(scene) { ScopeRenderer(scene, theme, density) }
    val overlay = remember(scene) { GameOverlay(density, theme) }
    renderer.setTheme(theme); overlay.setTheme(theme)
    val layer = rememberGraphicsLayer().also { it.compositingStrategy = CompositingStrategy.Offscreen }
    val cache = remember(scene) { StaticCache() }
    val settle = remember { mutableIntStateOf(0) }
    LaunchedEffect(camera) { snapshotFlow { camera.version }.debounce(120).collect { settle.intValue++ } }
    val fpsPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT; textSize = 9f * density }
    }
    val bg = Color(theme.bg)

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { s ->
                // The visible rectangle excludes the room the bottom action bar and the corner overlays take, so the
                // fit never changes when they appear. Insets are already applied by the parent layout.
                val d = density
                camera.setViewport(s.width.toFloat(), s.height.toFloat(), 12f * d, 10f * d, 12f * d, 84f * d, d)
            }
            .pointerInput(camera, game) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moving = false; var multi = false; var travel = Offset.Zero
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) multi = true
                        val pan = ev.calculatePan(); val zoom = ev.calculateZoom()
                        if (!moving) { travel += pan; if (multi || travel.getDistance() > slop) moving = true }
                        if (moving && (pan != Offset.Zero || zoom != 1f)) {
                            val c = ev.calculateCentroid()
                            if (c.isSpecified) camera.transform(c.x, c.y, pan.x, pan.y, zoom)
                            ev.changes.forEach { if (it.pressed) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    // Plain tap: acted on at release, no double-tap wait.
                    if (!moving && !multi) game.tap(down.position.x, down.position.y, camera, 48f * density)
                }
            },
    ) {
        game.frame           // game loop redraw: the only per-frame state read
        camera.version       // camera changes redraw this scope only
        val st = settle.intValue
        val w = size.width.toInt(); val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@Canvas
        val mx = (w * 0.2f).toInt(); val my = (h * 0.2f).toInt()

        var k = camera.zoom / cache.z
        var tx = (cache.cx - camera.cx) * camera.zoom; var ty = (camera.cy - cache.cy) * camera.zoom
        var need = !cache.valid || cache.w != w || cache.h != h || cache.theme !== theme || k < 0.7f || k > 1.4f ||
            abs(tx) > cache.mx * 0.8f || abs(ty) > cache.my * 0.8f
        if (st != cache.settle) { cache.settle = st; if (k != 1f || tx != 0f || ty != 0f) need = true }
        if (need) {
            layer.record(this, layoutDirection, IntSize(w + 2 * mx, h + 2 * my)) {
                drawIntoCanvas {
                    val c = it.nativeCanvas
                    c.save(); c.translate(mx.toFloat(), my.toFloat())
                    renderer.drawStatic(c, camera.cx, camera.cy, camera.zoom, camera.ax, camera.ay, w.toFloat(), h.toFloat())
                    c.restore()
                }
            }
            cache.valid = true; cache.cx = camera.cx; cache.cy = camera.cy; cache.z = camera.zoom
            cache.w = w; cache.h = h; cache.mx = mx; cache.my = my; cache.theme = theme
            k = 1f; tx = 0f; ty = 0f
        }
        drawRect(bg)
        withTransform({
            translate(tx, ty)
            scale(k, k, Offset(camera.ax, camera.ay))
            translate(-cache.mx.toFloat(), -cache.my.toFloat())
        }) { drawLayer(layer) }
        drawIntoCanvas {
            val c = it.nativeCanvas
            renderer.drawLabels(c, camera)
            overlay.draw(c, camera, game)
            if (BuildConfig.SHOW_FPS) {
                fpsPaint.color = if (game.fps >= 50f) 0xFF9AF0C0.toInt() else 0xFFFFB43A.toInt()
                c.drawText(game.fpsText, w - 10f * density, 52f * density, fpsPaint)
            }
        }
    }
}
