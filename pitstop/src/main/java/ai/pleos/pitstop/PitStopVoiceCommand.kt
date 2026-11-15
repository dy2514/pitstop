package ai.pleos.pitstop

import ai.pleos.pitstop.core.Candidate

data class PitStopVoiceCommand(val action: String, val accessible: Boolean = false, val alternative: Boolean = false) {
    fun requested(candidate: Candidate): GleoResult = GleoResult("success",
        "Navigation to the public restroom parking was requested; destination confirmation is pending.",
        listOf(buildMap<String, Any> {
            put("parkingName", candidate.pair.parkingName); put("navigationStatus", "requested")
            put("timeToToiletEstimateAvailable", candidate.tttSeconds != null)
            candidate.tttSeconds?.let { put("estimatedTimeToToiletSeconds", it) }
        }))
    companion object {
        const val FIND = "PITSTOP_FIND_RESTROOM"
        const val NEXT = "PITSTOP_NEXT_RESTROOM"
        const val RESUME = "PITSTOP_RESUME_DESTINATION"
        val actions = setOf(FIND, NEXT, RESUME)
        val parameters = setOf("accessible", "alternative")
        fun parse(input: GleoParameters, action: String = FIND): PitStopVoiceCommand {
            require(action in actions)
            if(action != FIND) { input.only(); return PitStopVoiceCommand(action, alternative=action==NEXT) }
            input.only(*parameters.toTypedArray())
            return PitStopVoiceCommand(action, input.flag("accessible", false), input.flag("alternative", false))
        }
    }
}
