package io.github.aviato28.scopecreep.scope

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.theme.ScopeTheme
import kotlinx.coroutines.launch

/** Pan / pinch-zoom / double-tap-zoom scope. Static geometry comes from the cached [scene]. */
@Composable
fun ScopeView(scene: ScopeScene, theme: ScopeTheme, game: GameController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val camera = remember(scene) { Camera(scene.bounds) }
    val renderer = remember(scene) { ScopeRenderer(scene, theme, density) }
    val overlay = remember(scene) { GameOverlay(density, theme) }
    renderer.setTheme(theme); overlay.setTheme(theme)
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(0f) }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) camera.zoomAbout(centroid.x, centroid.y, zoom)
                    camera.panBy(pan.x, pan.y)
                }
            }
            .pointerInput(camera) {
                detectTapGestures(onTap = { p -> game.tap(p.x, p.y, camera, 48f * density) }, onDoubleTap = { p ->
                    val from = camera.zoom
                    // zoom in to 2.5x (or back to fit when already well zoomed in)
                    val to = if (from > camera.fitZoom * 2f) camera.fitZoom else minOf(from * 2.5f, camera.maxZoom)
                    scope.launch {
                        anim.snapTo(0f)
                        var last = from
                        anim.animateTo(1f, tween(220)) {
                            val z = from * Math.pow((to / from).toDouble(), value.toDouble()).toFloat()
                            camera.zoomAbout(p.x, p.y, z / last); last = z
                        }
                    }
                })
            },
    ) {
        camera.setViewSize(size.width, size.height, density)
        game.frame // redraw every frame
        camera.version // read state so camera changes redraw this scope only
        drawIntoCanvas { renderer.draw(it.nativeCanvas, camera); overlay.draw(it.nativeCanvas, camera, game) }
    }
}
