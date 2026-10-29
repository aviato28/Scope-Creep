package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.VoblAirportTest
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** One frame: scope + taxiing aircraft + chevron, data block and cyan route. Writes only with -PrenderOut=<dir>. */
class SimRenderTest {
    @Test fun frame() {
        val out = System.getProperty("render.out") ?: return
        System.setProperty("java.awt.headless", "true")
        val a = VoblAirportTest.load(); val sim = Simulation(a, 42)
        var ac: Aircraft? = null; var routes = emptyList<io.github.aviato28.scopecreep.sim.routing.Route>()
        while (sim.time < 200) {
            sim.step(); ac = sim.aircraft.firstOrNull() ?: continue
            if (ac.state == AcState.REQUEST_PUSH) sim.submit(PushbackApproved(ac.id))
            if (ac.state == AcState.REQUEST_TAXI) { routes = sim.suggestRoutes(ac, sim.destinations.first()); sim.submit(Taxi(ac.id, routes[0])) }
            if (ac.state == AcState.TAXIING && ac.s > routes[0].lengthM * 0.35) break
        }
        ac!!
        val w = 1600; val h = 900; val k = w / 1500.0
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(5, 9, 10); g.fillRect(0, 0, w, h)
        fun sx(x: Double) = (x - ac.x) * k + w / 2
        fun sy(y: Double) = h / 2 - (y - ac.y) * k
        fun poly(xs: DoubleArray, ys: DoubleArray) = Path2D.Double().also { p -> xs.indices.forEach { i -> if (i == 0) p.moveTo(sx(xs[i]), sy(ys[i])) else p.lineTo(sx(xs[i]), sy(ys[i])) } }
        g.stroke = BasicStroke(1.5f)
        for (e in sim.graph.edges) { g.color = Color(42, 125, 87); g.draw(poly(e.xs, e.ys)) }
        for (r in a.runways) { g.color = Color(57, 217, 138); g.draw(poly(r.centerline.map { it[0] }.toDoubleArray(), r.centerline.map { it[1] }.toDoubleArray())) }
        for ((i, r) in routes.withIndex()) {
            g.color = if (i == 0) Color(95, 208, 232) else Color(95, 208, 232, 90)
            g.stroke = BasicStroke(if (i == 0) 4f else 2.5f); g.draw(poly(r.pts.filterIndexed { j, _ -> j % 2 == 0 }.toDoubleArray(), r.pts.filterIndexed { j, _ -> j % 2 == 1 }.toDoubleArray()))
        }
        g.color = Color(255, 180, 58); for (d in sim.destinations) g.drawOval(sx(d.x).toInt() - 9, sy(d.y).toInt() - 9, 18, 18)
        val px = sx(ac.x); val py = sy(ac.y)
        val gt = g.transform; g.translate(px, py); g.rotate(Math.toRadians(ac.heading)); g.scale(12.0, 12.0)
        g.color = Color(95, 208, 232)
        g.fill(Path2D.Double().apply { moveTo(0.0, -1.0); lineTo(0.75, 0.8); lineTo(0.0, 0.4); lineTo(-0.75, 0.8); closePath() }); g.transform = gt
        g.font = Font(Font.MONOSPACED, Font.BOLD, 14); g.drawLine(px.toInt() + 8, py.toInt() - 8, px.toInt() + 40, py.toInt() - 40)
        g.drawString(ac.callsign, px.toInt() + 44, py.toInt() - 44); g.drawString(ac.typeLine, px.toInt() + 44, py.toInt() - 30); g.drawString(ac.instruction, px.toInt() + 44, py.toInt() - 16)
        g.dispose(); File(out).mkdirs(); ImageIO.write(img, "png", File(out, "sim-frame.png"))
    }
}
