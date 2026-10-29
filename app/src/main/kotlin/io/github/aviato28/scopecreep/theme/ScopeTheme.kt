package io.github.aviato28.scopecreep.theme

/** Colour (ARGB ints) and stroke tokens for the scope. Stroke widths are in dp. */
data class ScopeTheme(
    val name: String,
    val bg: Int, val panel: Int, val panelLine: Int,
    val apronFill: Int, val apronStroke: Int,
    val buildingFill: Int, val buildingStroke: Int,
    val taxiBand: Int, val taxiLine: Int, val laneLine: Int,
    val runwayFill: Int, val runwayEdge: Int, val runwayMark: Int,
    val hold: Int, val stand: Int, val standText: Int,
    val label: Int, val labelBg: Int, val text: Int, val dim: Int, val accent: Int,
    val laneStrokeDp: Float, val taxiStrokeDp: Float, val wideStrokeDp: Float,
    val runwayEdgeDp: Float, val holdStrokeDp: Float, val standStrokeDp: Float,
    val departure: Int = 0xFFFFB43A.toInt(), val selected: Int = 0xFF5FD0E8.toInt(), val routeDim: Int = 0x805FD0E8.toInt(),
    val warn: Int = 0xFFFF4D4D.toInt(), val arrival: Int = 0xFF6EE7A8.toInt(),
) {
    /** Night / dim variant: dimmer fills and lines, labels and text only slightly reduced so they stay readable. Cached per theme in [Dim]. */
    fun dimmed(): ScopeTheme {
        fun k(c: Int, f: Float): Int {
            val r = ((c shr 16 and 0xFF) * f).toInt(); val g = ((c shr 8 and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
            return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        val fill = 0.55f; val line = 0.62f; val txt = 0.8f
        return copy(
            name = "$name Dim",
            bg = k(bg, 0.6f), panel = k(panel, fill), panelLine = k(panelLine, line),
            apronFill = k(apronFill, fill), apronStroke = k(apronStroke, line), buildingFill = k(buildingFill, fill), buildingStroke = k(buildingStroke, line),
            taxiBand = k(taxiBand, fill), taxiLine = k(taxiLine, line), laneLine = k(laneLine, line),
            runwayFill = k(runwayFill, fill), runwayEdge = k(runwayEdge, 0.7f), runwayMark = k(runwayMark, line),
            hold = k(hold, 0.75f), stand = k(stand, line), standText = k(standText, 0.7f),
            label = k(label, txt), labelBg = k(labelBg, 0.6f), text = k(text, txt), dim = k(dim, 0.85f), accent = k(accent, txt),
            departure = k(departure, 0.8f), selected = k(selected, 0.8f), routeDim = k(routeDim, 0.8f), warn = k(warn, 0.85f), arrival = k(arrival, 0.8f),
        )
    }

    companion object {
        /** Values from mockup 01 (Phosphor Classic). */
        val Phosphor = ScopeTheme(
            "Phosphor", bg = 0xFF05090A.toInt(), panel = 0xFF0A1214.toInt(), panelLine = 0xFF1F5A3A.toInt(),
            apronFill = 0xFF071312.toInt(), apronStroke = 0xFF1F5A3A.toInt(),
            buildingFill = 0xFF0C1F1C.toInt(), buildingStroke = 0xB339D98A.toInt(),
            taxiBand = 0xFF0A1D17.toInt(), taxiLine = 0xFF2A7D57.toInt(), laneLine = 0xFF1F5A3A.toInt(),
            runwayFill = 0xFF07130F.toInt(), runwayEdge = 0xFF39D98A.toInt(), runwayMark = 0xFF1F5A3A.toInt(),
            hold = 0xFFFFB43A.toInt(), stand = 0xFF2F7A58.toInt(), standText = 0xFF4F8F6E.toInt(),
            label = 0xFFFFB43A.toInt(), labelBg = 0xD905090A.toInt(), text = 0xFF9AF0C0.toInt(), dim = 0xFF4F8F6E.toInt(),
            accent = 0xFF39D98A.toInt(),
            laneStrokeDp = 0.8f, taxiStrokeDp = 1.3f, wideStrokeDp = 1.8f,
            runwayEdgeDp = 1.4f, holdStrokeDp = 2.2f, standStrokeDp = 1f,
        )

        /** Modern Surface (mockup 02): lighter, filled surfaces. Tuned later; switchable from settings. */
        val ModernSurface = ScopeTheme(
            "Modern Surface", bg = 0xFF11171C.toInt(), panel = 0xFF161D23.toInt(), panelLine = 0xFF2B3640.toInt(),
            apronFill = 0xFF1C252C.toInt(), apronStroke = 0xFF2B3640.toInt(),
            buildingFill = 0xFF28333C.toInt(), buildingStroke = 0xFF3A4753.toInt(),
            taxiBand = 0xFF2A343D.toInt(), taxiLine = 0xFF5C6B78.toInt(), laneLine = 0xFF45525D.toInt(),
            runwayFill = 0xFF39434C.toInt(), runwayEdge = 0xFFB8C4CE.toInt(), runwayMark = 0xFFE8EEF2.toInt(),
            hold = 0xFFFFB43A.toInt(), stand = 0xFF6B7A87.toInt(), standText = 0xFF8FA0AE.toInt(),
            label = 0xFFFFD27A.toInt(), labelBg = 0xD911171C.toInt(), text = 0xFFD5DEE5.toInt(), dim = 0xFF8FA0AE.toInt(),
            accent = 0xFF5FD0E8.toInt(),
            laneStrokeDp = 0.8f, taxiStrokeDp = 1.4f, wideStrokeDp = 2f,
            runwayEdgeDp = 1.2f, holdStrokeDp = 2.2f, standStrokeDp = 1f,
        )

        val PhosphorDim = Phosphor.dimmed()
        val ModernSurfaceDim = ModernSurface.dimmed()
    }
}
