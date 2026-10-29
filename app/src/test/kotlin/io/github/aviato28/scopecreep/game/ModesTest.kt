package io.github.aviato28.scopecreep.game

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModesTest {
    @Test fun `daily seed is stable per UTC date and differs between days`() {
        val d = LocalDate.of(2026, 10, 29)
        assertEquals(Modes.dailySeed(d), Modes.dailySeed(d))
        assertNotEquals(Modes.dailySeed(d), Modes.dailySeed(d.plusDays(1)))
        assertNotEquals(Modes.dailySeed(d, "VOBL"), Modes.dailySeed(d, "VIDP"))
    }

    @Test fun `mode configs`() {
        assertEquals(0.0, Modes.config(GameMode.ENDLESS, false).shiftSeconds)
        assertEquals(420.0, Modes.config(GameMode.SHIFT, false).shiftSeconds)
        assertTrue(Modes.config(GameMode.SHIFT, false).strikesEndShift)
        val t = Modes.config(GameMode.SHIFT, true)
        assertFalse(t.strikesEndShift); assertTrue(t.spawnIntervalAt0 < Modes.config(GameMode.SHIFT, false).spawnIntervalAt0)
    }
}
