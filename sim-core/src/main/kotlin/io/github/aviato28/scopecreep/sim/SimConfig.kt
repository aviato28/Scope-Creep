package io.github.aviato28.scopecreep.sim

/** All tunables in one place (design.md: values marked tune live here, not scattered in code). */
data class SimConfig(
    val stepSeconds: Double = 0.1,
    /** Departure runway end in use ("27s" config: departures on 27R). */
    val departureRunway: String = "27R",
    val maxCatchUpSeconds: Double = 0.5,
    val timeScale: Double = 1.0,

    // pilot request timing (s)
    val atStandDelayMin: Double = 2.0, val atStandDelayMax: Double = 5.0,
    val requestTaxiDelayMin: Double = 3.0, val requestTaxiDelayMax: Double = 6.0,
    val readyForDepartureDelay: Double = 8.0,
    val readbackDelay: Double = 1.5,
    val nextSpawnDelayMin: Double = 4.0, val nextSpawnDelayMax: Double = 8.0,

    // movement (m/s, m/s^2)
    val taxiSpeed: Double = 7.7,       // 15 kt on straights
    val turnSpeed: Double = 3.0,       // ~6 kt in a 90 degree turn
    val turnFullSpeedBelowDeg: Double = 15.0,
    val turnSlowestAtDeg: Double = 90.0,
    val accel: Double = 1.2, val decel: Double = 2.0,
    val pushSpeedMax: Double = 3.0,
    val pushDurationMin: Double = 30.0, val pushDurationMax: Double = 45.0,
    val headingRateDegPerS: Double = 70.0,
    val rotateSpeed: Double = 75.0, val takeoffAccel: Double = 2.5,
    val airborneSeconds: Double = 10.0,
    val handoffExtensionM: Double = 2500.0,

    /** Holds farther than this (graph metres) from the runway are not offered as departure holds. */
    val maxHoldToRunwayM: Double = 700.0,
)
