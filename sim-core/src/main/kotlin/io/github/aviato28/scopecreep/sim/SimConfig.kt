package io.github.aviato28.scopecreep.sim

import kotlinx.serialization.Serializable

/** All tunables in one place (design.md: values marked tune live here, not scattered in code). Serializable so a replay carries the exact tunables. */
@Serializable
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

    // traffic ramp (departures only): pressure 0.5 at 10 min, 1.0 at 25 min
    val pressureHalfAtS: Double = 600.0, val pressureFullAtS: Double = 1500.0,
    val spawnIntervalAt0: Double = 150.0, val spawnIntervalAt1: Double = 35.0, val spawnJitter: Double = 0.3,
    val spawnRetryS: Double = 10.0,
    /** Pilot re-calls an ignored request after this long, then every this long again. */
    val recallSeconds: Double = 60.0,
    // separation
    val spacingM: Double = 60.0, val lateralM: Double = 14.0, val scanAheadM: Double = 140.0,
    /** Max angle between the facing after pushback and the first taxi edge (more is a U-turn, not allowed in v1). */
    val maxFirstTurnDeg: Double = 100.0,
    val maxStepsPerUpdate: Int = 60,

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

    // ---- arrivals (M3) ----
    /** Arrival runway end at the start of a session (the player can change the configuration). */
    val arrivalRunway: String = "27L",
    val arrivalsEnabled: Boolean = true,
    /** Mean seconds between arrivals at pressure 0 / 1 (floor keeps final spacing workable: ~90 s). */
    val arrivalIntervalAt0: Double = 150.0, val arrivalIntervalAt1: Double = 90.0,
    /** Arrivals appear this many seconds before touchdown (hand-off zone), uniformly in [min, max]. */
    val finalLeadMin: Double = 65.0, val finalLeadMax: Double = 95.0,
    val approachSpeed: Double = 80.0,        // m/s (~155 kt) on final
    val touchdownSpeed: Double = 70.0,       // m/s
    val touchdownZoneM: Double = 300.0,      // touchdown point beyond the threshold
    val exitSpeed: Double = 12.0,            // m/s when turning off
    val rolloutDecel: Double = 3.0, val rolloutMaxDecel: Double = 6.0,
    /** Rapid-exit classification (decision 22 follow-up): angle window (deg) of an exit connector relative to the landing direction. */
    val retMinDeg: Double = 10.0, val retMaxDeg: Double = 60.0,
    /** No exit is offered closer to the threshold than this (m): jets, turboprops, heavies. Default exit: first rapid exit beyond the typical rollout. */
    val exitMinJetM: Double = 1200.0, val exitMinTurbopropM: Double = 800.0, val exitMinHeavyM: Double = 1500.0,
    val rolloutJetM: Double = 1350.0, val rolloutTurbopropM: Double = 900.0, val rolloutHeavyM: Double = 1800.0,
    // ---- follow-me ----
    /** Follow-me leads at ~10 kt, drives to the aircraft faster, and the aircraft keeps ~40 m behind it. */
    val followMeSpeed: Double = 5.1, val followMeApproachSpeed: Double = 8.0, val followMeGapM: Double = 40.0,
    /** Landing decision point (m before the threshold): runway must be clear; cleared-to-land needed before [noClearanceGoAroundM]. */
    val decisionM: Double = 1852.0, val noClearanceGoAroundM: Double = 926.0, val landingRecallM: Double = 3700.0,
    val goAroundSeconds: Double = 30.0, val goAroundRejoinMin: Double = 180.0, val goAroundRejoinMax: Double = 240.0,
    /** Arrivals use the new arrival runway this long after a configuration change. */
    val arrivalSwitchDelayS: Double = 120.0,
    /** Taxi-in re-call interval for a vacated arrival waiting for a route. */
    val taxiInRecallSeconds: Double = 30.0,
    // crossings
    val crossHoldM: Double = 70.0, val crossBeforeM: Double = 45.0, val crossAfterM: Double = 70.0,

    // ---- wind (decision 20) ----
    val windStartDirDeg: Double = Double.NaN, val windStartKt: Double = Double.NaN,
    val windDrift: Boolean = true, val windDriftDegPerKey: Double = 6.0,
    val windMaxKtAt0: Double = 14.0, val windMaxKtAt1: Double = 26.0,
    val windVeerChanceAt0: Double = 0.01, val windVeerChanceAt1: Double = 0.06,
    /** Tailwind above this (kt) makes arrivals report it and sometimes go around; departures ask for the other direction. */
    val tailwindLimitKt: Double = 5.0,
    /** Go-around probability = (tailwind - limit) * this, capped at [tailwindGoAroundMax]. */
    val tailwindGoAroundPerKt: Double = 0.07, val tailwindGoAroundMax: Double = 0.85,
    /** Wind-shift warning is logged this long before the favoured direction flips (random in [min,max] per flip). */
    val windWarnMinS: Double = 120.0, val windWarnMaxS: Double = 240.0,

    // ---- conflicts (batch 2a) ----
    val headOnStrikeSeconds: Double = 20.0,
    val junctionWindowS: Double = 10.0, val junctionStopM: Double = 25.0,

    /** Holds farther than this (graph metres) from the runway are not offered as departure holds. */
    val maxHoldToRunwayM: Double = 700.0,

    // ---- scoring (design.md section 7) ----
    val basePointsDeparture: Int = 100, val basePointsArrival: Int = 100, val basePointsTow: Int = 60,
    /** -1 per second beyond this grace per wait (request to clearance/route), capped at [delayPenaltyCap]. */
    val delayGraceS: Double = 30.0, val delayPenaltyCap: Int = 50,
    /** Efficiency bonus: full within [effFullRatio] x shortest, falling linearly to 0 at [effZeroRatio]. */
    val efficiencyMax: Int = 20, val effFullRatio: Double = 1.1, val effZeroRatio: Double = 1.5,
    val streakStep: Double = 0.1, val streakCap: Double = 2.0,
    val strikePenalty: Int = 150, val maxStrikes: Int = 3,

    // ---- shift flow ----
    /** Shift length in seconds; 0 = endless (the default for a bare Simulation; the app sets 420 for Shift/Daily). */
    val shiftSeconds: Double = 0.0,
    /** When false (default for a bare Simulation, and the first-ever tutorial shift) strikes are counted but never end the shift. */
    val strikesEndShift: Boolean = false,
    /** Departure slots (TSAT / CTOT), pushback start-up and lane blocking. */
    val slots: SlotConfig = SlotConfig(),
    /** Conflict prediction. */
    val predict: PredictConfig = PredictConfig(),
    /** Operational events. */
    val events: EventConfig = EventConfig(),
    /** In mixed mode (one runway for both) arrivals are at least this far apart, and never spawn while a departure is lined up or rolling. */
    val mixedArrivalIntervalS: Double = 120.0,
) {
    private fun turboprop(t: AircraftType) = t.code in setOf("AT72", "AT76", "DH8C", "DH8D", "E120")
    fun exitMinM(t: AircraftType) = if (t.wake >= Wake.H) exitMinHeavyM else if (turboprop(t)) exitMinTurbopropM else exitMinJetM
    fun rolloutM(t: AircraftType) = if (t.wake >= Wake.H) rolloutHeavyM else if (turboprop(t)) rolloutTurbopropM else rolloutJetM
}

/** Departure slots (TSAT / CTOT), pushback start-up and taxilane blocking. */
@Serializable
data class SlotConfig(
    /** TSAT = spawn + random [tsatMinS, tsatMaxS]; the pilot requests push then. */
    val tsatMinS: Double = 0.0, val tsatMaxS: Double = 30.0,
    /** CTOT = TSAT + this (push, start-up and a typical taxi) + jitter. Window = [CTOT - early, CTOT + late]. Real life is -5/+10 min; scaled for 5-8 min shifts. */
    val ctotNominalS: Double = 330.0, val ctotJitterS: Double = 45.0,
    val ctotEarlyS: Double = 120.0, val ctotLateS: Double = 240.0,
    /** Points for a takeoff inside / outside the CTOT window. */
    val ctotBonus: Int = 25, val ctotPenalty: Int = 25,

    /** After the push the aircraft starts engines for this long (STARTING) before it calls for taxi; it blocks the lane meanwhile. */
    val startupMinS: Double = 60.0, val startupMaxS: Double = 90.0,
    /** Router cost (m) added to each taxilane segment blocked by a pushing / starting aircraft (high, not impossible). */
    val blockedEdgeCostM: Double = 500.0
)

/** Conflict prediction (see [ConflictPredictor]). */
@Serializable
data class PredictConfig(
    val predictHorizonS: Double = 45.0, val predictEveryS: Double = 0.5,
    /** Predicted head-on: two aircraft facing each other closer than this (m); the simulation itself stops nose-to-nose pairs about 60 m apart. */
    val predictHeadOnM: Double = 70.0,
    /** Predicted junction conflict: both reach one node within this many seconds. */
    val predictJunctionS: Double = 8.0
)

/** Operational events (design.md section 8); off for a bare Simulation, the app enables them. */
@Serializable
data class EventConfig(
    val eventsEnabled: Boolean = false,
    /** Event pressure on the design scale 0-10: start value + elapsed / [opsPressureRiseS] (design: +1 per 90 s). */
    val opsPressureStart: Double = 0.0, val opsPressureRiseS: Double = 90.0,
    /** Dice roll every [eventCheckS]; per-minute chance = [eventPerMinAt5] at pressure 5 rising linearly to [eventPerMinAt10] at 10. */
    val eventCheckS: Double = 15.0, val eventPerMinAt5: Double = 0.15, val eventPerMinAt10: Double = 0.45,
    /** Bands: the event types unlock at these pressures; two events may overlap from [eventOverlapPressure]. */
    val hardBandPressure: Double = 5.0, val brutalBandPressure: Double = 7.0, val eventOverlapPressure: Double = 7.0,
    /** Emergency: PAN (vs MAYDAY) share, chance to stop on the runway after landing, stop length and the fire-service hold. */
    val panShare: Double = 0.4, val emergencyStopChance: Double = 0.7, val emergencyStopMinS: Double = 120.0, val emergencyStopMaxS: Double = 240.0,
    /** Departures wait while an emergency is this close (s) to touchdown. */
    val emergencyReserveS: Double = 150.0,
    /** Rejected takeoff: abort speed range (m/s) and braking (m/s^2). */
    val rtoSpeedMin: Double = 30.0, val rtoSpeedMax: Double = 55.0, val rtoDecel: Double = 4.0,
    /** LVP: duration, RVR (m), traffic-rate factor, extra departure spacing and no LINE UP with an arrival inside [lvpArrivalNm]. */
    val lvpMinS: Double = 150.0, val lvpMaxS: Double = 240.0, val lvpRvrM: Int = 550, val lvpRateFactor: Double = 0.7,
    val lvpDepSpacingS: Double = 90.0, val lvpArrivalNm: Double = 4.0, val lvpWarnS: Double = 20.0,
    /** Runway inspection / closure: duration and the lead time between the call and the vehicle entering. */
    val closureMinS: Double = 90.0, val closureMaxS: Double = 150.0, val closureLeadS: Double = 15.0,
    /** Bird activity: duration, chance an arrival goes around, chance a departure is delayed, and the delay. */
    val birdMinS: Double = 90.0, val birdMaxS: Double = 150.0, val birdGoAroundChance: Double = 0.25, val birdDelayChance: Double = 0.6, val birdDelayS: Double = 30.0
)
