package com.example.zepp_gestures

/**
 * Resolves the next [VibrationCommand] to send to the watch based on the
 * current tick's gesture / scoring / passivity events.
 *
 * Holds the small amount of state required to bridge between ticks --
 * specifically the per-gesture flick count latched at the end of a scoring
 * sequence -- so that the gesture-recognition pipeline doesn't have to
 * track vibration-specific bookkeeping itself.
 *
 * Thread-safe: all mutable state is guarded by [lock]. The class never
 * calls back into its caller, so nesting [lock] inside another lock is
 * safe (no deadlock potential).
 */
class VibrationResolver {

    private val lock = Any()

    // Number of points scored within the just-ended scoring sequence,
    // latched by [latchScoringExitFlicks] and consumed on the next
    // [resolve] call. When non-null on a tick it produces the long-buzz
    // + N-tap "scoring complete" pattern.
    private var pendingScoringExitFlicks: Int? = null

    /**
     * Latch the per-gesture flick count to be turned into the long+N tap
     * vibration on the next [resolve] call. Should be invoked exactly
     * once when a scoring sequence transitions back to the WAITING mode.
     */
    fun latchScoringExitFlicks(count: Int) {
        synchronized(lock) {
            pendingScoringExitFlicks = count
        }
    }

    /**
     * Drop any latched state. Called when the entire match is reset.
     */
    fun reset() {
        synchronized(lock) {
            pendingScoringExitFlicks = null
        }
    }

    /**
     * Compute the [VibrationCommand] for this tick. Higher-priority
     * signals (passivity penalty, Touche, scoring exit) override lower-
     * priority mode-change buzzes.
     *
     * The latched flick count is always consumed, even if a higher-
     * priority signal pre-empts the long+N pattern, so a stale value
     * can never leak into a later tick.
     */
    fun resolve(
        modeChange: Pair<GestureMode, GestureMode>,
        passivityStarted: Boolean,
        passivityExpired: Boolean,
        newEventGestures: Set<String>
    ): VibrationCommand {
        val flickCount = synchronized(lock) {
            val v = pendingScoringExitFlicks
            pendingScoringExitFlicks = null
            v
        }
        if (passivityExpired) return VibrationCommand(1, "long")
        if ("Touche" in newEventGestures) return VibrationCommand(5, "short")
        // Scoring sequence just ended: long buzz + N shorts (one per flick).
        if (flickCount != null) {
            return VibrationCommand(
                count = 1,
                duration = "long",
                followupCount = flickCount,
                followupDuration = "short"
            )
        }
        if (passivityStarted) return VibrationCommand(2, "short")
        val (previous, current) = modeChange
        if (previous != current) {
            if (current.isWarning) return VibrationCommand(2, "short")
            // Entering a scoring gesture (hand_up -> GESTURE_RED,
            // hand_back -> GESTURE_BLUE) always gets a distinctive
            // short-strong buzz so the referee gets a tactile
            // confirmation that scoring just armed -- in both debug
            // and prod modes.
            if (current.isScoring) return VibrationCommand(1, "short_strong")
            return VibrationCommand(1, "short")
        }
        return VibrationCommand(0, "short")
    }
}
