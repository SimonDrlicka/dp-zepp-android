package com.example.zepp_gestures

class VibrationResolver(

    private val vibrateOnScoringArmed: Boolean = true
) {

    private val lock = Any()

    private var pendingScoringExitFlicks: Int? = null

    fun latchScoringExitFlicks(count: Int) {
        synchronized(lock) {
            pendingScoringExitFlicks = count
        }
    }

    fun reset() {
        synchronized(lock) {
            pendingScoringExitFlicks = null
        }
    }

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

            if (current.isScoring) {
                return if (vibrateOnScoringArmed) VibrationCommand(1, "short_strong")
                else VibrationCommand(0, "short")
            }
            return VibrationCommand(1, "short")
        }
        return VibrationCommand(0, "short")
    }
}
