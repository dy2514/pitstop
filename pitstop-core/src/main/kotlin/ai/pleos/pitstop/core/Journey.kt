package ai.pleos.pitstop.core

/** Route-specific estimates. Null means unknown, never zero. All durations are seconds. */
data class ToiletTiming(
    val drive: Int?, val signal: Int?, val signalsIncluded: Boolean,
    val parking: Int?, val walk: Int?, val floors: Int?, val queue: Int?,
    val observedAtMs: Long, val source: String, val origin: GeoPoint? = null,
) {
    init {
        require(listOf(drive, signal, parking, walk, floors, queue).all { it == null || it in 0..86400 })
        require(source.isNotBlank())
    }
    fun total(nowMs: Long, currentOrigin: GeoPoint? = null): Int? {
        if(currentOrigin != null && (origin == null || origin.metersTo(currentOrigin) > 50.0)) return null
        if (nowMs - observedAtMs !in 0..300_000) return null
        val components = listOf(drive, if (signalsIncluded) 0 else signal, parking, walk, floors, queue)
        if (components.any { it == null }) return null
        return components.sumOf { it!! }
    }
}

enum class Vent { FACE, FLOOR, DEFROST, OTHER }
data class Comfort(val vent: Vent, val seatHeat: Int, val wheelHeat: Int, val media: Int, val ambient: String)
data class CareOptions(val warmth: Boolean = false, val redirect: Boolean = false, val quiet: Boolean = false,
    val durationMs: Long = 180_000) { init { require(durationMs in 10_000..300_000) } }

/** Pure policy; an adapter must check capabilities and confirm each write before owning it. */
class CareLease(val before: Comfort, val options: CareOptions, val startedAt: Long) {
    val target = before.copy(
        vent = if (options.redirect && before.vent == Vent.FACE) Vent.FLOOR else before.vent,
        seatHeat = if (options.warmth) 3 else before.seatHeat,
        wheelHeat = if (options.warmth) 3 else before.wheelHeat,
        media = if (options.quiet) 0 else before.media,
        ambient = if (options.quiet) "CALM" else before.ambient,
    )
    private var confirmed = false
    private val relinquished = mutableSetOf<String>()
    fun confirm(actual: Comfort) { check(actual == target); confirmed = true }
    fun observe(actual: Comfort) {
        if (!confirmed) return
        if (actual.vent != target.vent) relinquished += "vent"
        if (actual.seatHeat != target.seatHeat) relinquished += "seat"
        if (actual.wheelHeat != target.wheelHeat) relinquished += "wheel"
        if (actual.media != target.media) relinquished += "media"
        if (actual.ambient != target.ambient) relinquished += "ambient"
    }
    fun expired(now: Long) = now - startedAt >= options.durationMs
    fun restore(actual: Comfort): Comfort {
        if (!confirmed) return actual
        observe(actual)
        return actual.copy(
            vent = if ("vent" !in relinquished) before.vent else actual.vent,
            seatHeat = if ("seat" !in relinquished) before.seatHeat else actual.seatHeat,
            wheelHeat = if ("wheel" !in relinquished) before.wheelHeat else actual.wheelHeat,
            media = if ("media" !in relinquished) before.media else actual.media,
            ambient = if ("ambient" !in relinquished) before.ambient else actual.ambient,
        )
    }
}

data class ArrivalSignal(val distanceM: Double, val accuracyM: Double, val fixAgeMs: Long,
    val speedKmh: Double, val speedAgeMs: Long, val gearP: Boolean, val beltReleased: Boolean,
    val permittedRoadsideStop: Boolean)
data class ArrivalConsent(val closeWindows: Boolean = false, val hazards: Boolean = false, val unlock: Boolean = false)
enum class ArrivalAction { CLOSE_WINDOWS, HAZARDS_ON, UNLOCK, ENTRANCE_BRIEFING }
class ArrivalGate {
    private var macroIssued = false
    private var briefingIssued = false
    fun evaluate(s: ArrivalSignal, consent: ArrivalConsent): Set<ArrivalAction> {
        if (!s.distanceM.isFinite() || !s.accuracyM.isFinite() || !s.speedKmh.isFinite() ||
            s.distanceM < 0 || s.accuracyM !in 0.0..15.0 || s.distanceM + s.accuracyM > 30.0 ||
            s.fixAgeMs !in 0..5000 || s.speedAgeMs !in 0..1000 || s.speedKmh != 0.0 || !s.gearP) return emptySet()
        return buildSet {
            if (!macroIssued) {
                if (consent.closeWindows) add(ArrivalAction.CLOSE_WINDOWS)
                if (consent.hazards && s.permittedRoadsideStop) add(ArrivalAction.HAZARDS_ON)
                if (consent.unlock) add(ArrivalAction.UNLOCK)
                macroIssued = true
            }
            if (s.beltReleased && !briefingIssued) { add(ArrivalAction.ENTRANCE_BRIEFING); briefingIssued = true }
        }
    }
}

enum class HazardKind { BUMP, CURVE }
data class RoadHazard(val id: String, val kind: HazardKind, val distanceM: Double, val observedAtMs: Long)
class SmoothCoach {
    private val announced = mutableSetOf<String>()
    private var lastCoaching: Long? = null
    fun hazard(h: RoadHazard, now: Long, speedKmh: Double): String? {
        if (!speedKmh.isFinite() || speedKmh <= 0 || !h.distanceM.isFinite() || h.id.isBlank() ||
            now-h.observedAtMs !in 0..5000 || h.distanceM !in 0.0..maxOf(30.0, speedKmh / 3.6 * 8) || !announced.add(h.id)) return null
        return if(h.kind == HazardKind.BUMP) "앞에 과속방지턱이 있어요. 부드럽게 감속하세요." else "앞에 급커브가 있어요. 속도를 천천히 줄이세요."
    }
    fun acceleration(metersPerSecondSquared: Double, now: Long, sampleAgeMs: Long): String? {
        if (!metersPerSecondSquared.isFinite() || sampleAgeMs !in 0..1000 || kotlin.math.abs(metersPerSecondSquared) < 2.5 ||
            lastCoaching?.let { now - it < 15_000 } == true) return null
        lastCoaching = now
        return "급한 페달 조작이 감지됐어요. 부드럽게 운전하세요."
    }
}
