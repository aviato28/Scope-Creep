package io.github.aviato28.scopecreep.sim.airport

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Sanity render (north up). Only writes output when -PrenderOut=<dir> is given to Gradle. */
class AirportRenderTest {
    @Test fun render() {
        val out = System.getProperty("render.out") ?: return
        System.setProperty("java.awt.headless", "true")
        val a = VoblAirportTest.load()
        val pos = a.graph.nodes.associateBy { it.id }
        val xs = a.graph.nodes.map { it.x }; val ys = a.graph.nodes.map { it.y }
        val minX = xs.min() - 100; val maxX = xs.max() + 100; val minY = ys.min() - 100; val maxY = ys.max() + 100
        val w = 2400; val k = w / (maxX - minX); val h = ((maxY - minY) * k).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(5, 9, 10); g.fillRect(0, 0, w, h)
        fun sx(x: Double) = (x - minX) * k
        fun sy(y: Double) = (maxY - y) * k
        fun poly(pts: List<List<Double>>, close: Boolean): Path2D.Double {
            val p = Path2D.Double()
            pts.forEachIndexed { i, q -> if (i == 0) p.moveTo(sx(q[0]), sy(q[1])) else p.lineTo(sx(q[0]), sy(q[1])) }
            if (close) p.closePath(); return p
        }
        for (ap in a.aprons) { g.color = Color(7, 19, 18); g.fill(poly(ap.polygon, true)); g.color = Color(31, 90, 58); g.draw(poly(ap.polygon, true)) }
        for (b in a.buildings) { g.color = Color(12, 31, 28); g.fill(poly(b.polygon, true)) }
        g.stroke = BasicStroke(1.5f)
        for (e in a.graph.edges) {
            val f = pos.getValue(e.from); val t = pos.getValue(e.to)
            g.color = when (e.kind) { "runway" -> Color(57, 217, 138); "taxilane" -> Color(30, 80, 60); else -> Color(42, 125, 87) }
            g.draw(poly(listOf(listOf(f.x, f.y)) + e.shape + listOf(listOf(t.x, t.y)), false))
        }
        g.color = Color(255, 180, 58)
        for (hd in a.holds) { val n = pos[hd.node] ?: continue; g.fillOval(sx(n.x).toInt() - 4, sy(n.y).toInt() - 4, 8, 8) }
        g.color = Color(80, 150, 110)
        for (s in a.stands) g.drawOval(sx(s.x).toInt() - 2, sy(s.y).toInt() - 2, 4, 4)
        g.color = Color.WHITE
        for (r in a.runways) for (e in r.ends) g.drawString(e.designator, sx(e.threshold.x).toInt() - 30, sy(e.threshold.y).toInt())
        g.dispose()
        File(out).mkdirs()
        ImageIO.write(img, "png", File(out, "VOBL.png"))
    }
}
