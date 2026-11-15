package ai.pleos.pitstop.core

import java.time.*
import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun metersTo(other: GeoPoint): Double {
        val a = Math.toRadians(latitude); val b = Math.toRadians(other.latitude)
        val d = sin((b-a)/2).pow(2) + cos(a)*cos(b)*sin(Math.toRadians(other.longitude-longitude)/2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(d.coerceIn(0.0, 1.0)))
    }
}

enum class OpenState { OPEN, CLOSED, UNKNOWN }
enum class FacilityKind { PUBLIC_TOILET, OTHER, UNKNOWN }
enum class AccessRestriction { NONE, CUSTOMER_ONLY, AUTHORIZATION_REQUIRED, UNKNOWN }
data class DailyWindow(val days: Set<Int>, val startMinute: Int, val endMinute: Int) {
    init { require(days.isNotEmpty() && days.all { it in 1..7 }); require(startMinute in 0..1439 && endMinute in 1..1440 && startMinute != endMinute) }
    fun includes(at: ZonedDateTime): Boolean {
        val minute = at.hour*60+at.minute
        return if (endMinute > startMinute) at.dayOfWeek.value in days && minute >= startMinute && minute < endMinute
        else (at.dayOfWeek.value in days && minute >= startMinute) || (at.minusDays(1).dayOfWeek.value in days && minute < endMinute)
    }
}
data class OpeningHours(val known: Boolean = false, val windows: List<DailyWindow> = emptyList(), val closedDates: Set<LocalDate> = emptySet()) {
    fun state(at: ZonedDateTime): OpenState = when {
        at.toLocalDate() in closedDates -> OpenState.CLOSED
        !known -> OpenState.UNKNOWN
        windows.any { it.includes(at) } -> OpenState.OPEN
        else -> OpenState.CLOSED
    }
}
data class Vacancy(val spaces: Int, val observedAtMs: Long) {
    init { require(spaces >= 0) }
    fun fresh(nowMs: Long) = nowMs - observedAtMs in 0..120_000
}
data class FacilityPair(
    val id: String, val toiletName: String, val parkingName: String, val address: String,
    val parkingPoint: GeoPoint, val coordinatesVerified: Boolean,
    val publicAccess: Boolean, val accessibleToilet: Boolean?,
    val toiletHours: OpeningHours, val parkingHours: OpeningHours,
    val walkingMeters: Int?, val walkingNote: String,
    val fee: String, val source: String, val sourceDate: LocalDate,
    val vacancy: Vacancy? = null,
    val timing: ToiletTiming? = null,
    val facilityKind: FacilityKind = FacilityKind.UNKNOWN,
    val accessRestriction: AccessRestriction = AccessRestriction.UNKNOWN,
) {
    val eligiblePublicToilet: Boolean get() = publicAccess &&
        facilityKind == FacilityKind.PUBLIC_TOILET && accessRestriction == AccessRestriction.NONE
    init {
        require(id.isNotBlank() && toiletName.isNotBlank() && parkingName.isNotBlank() && source.isNotBlank())
        require(walkingMeters == null || walkingMeters in 0..5000)
    }
}
enum class Priority { NEARBY, LESS_WALKING, FASTEST_TOILET }
data class SearchOptions(val priority: Priority = Priority.NEARBY, val requireAccessible: Boolean = false, val includeUnknownHours: Boolean = true, val radiusMeters: Double = 15_000.0)
data class Candidate(val pair: FacilityPair, val straightMeters: Double, val open: OpenState, val vacancy: Int?, val warnings: List<String>, val tttSeconds: Int? = null)
data class SearchResult(val candidates: List<Candidate>, val excluded: Int, val message: String)

/** No learned claims, fabricated ETA, inferred road legality, or occupancy from capacity. */
object PitStopEngine {
    fun search(origin: GeoPoint, fixAtMs: Long, pairs: List<FacilityPair>, at: ZonedDateTime, options: SearchOptions = SearchOptions()): SearchResult {
        val now = at.toInstant().toEpochMilli()
        require(options.radiusMeters.isFinite() && options.radiusMeters in 100.0..50_000.0)
        if (now-fixAtMs !in 0..30_000) return SearchResult(emptyList(), pairs.size, "현재 위치를 다시 확인해 주세요.")
        val candidates = pairs.distinctBy { it.id }.mapNotNull { pair ->
            val distance = origin.metersTo(pair.parkingPoint)
            val toilet = pair.toiletHours.state(at); val parking = pair.parkingHours.state(at)
            val open = if (toilet == OpenState.CLOSED || parking == OpenState.CLOSED) OpenState.CLOSED
                else if (toilet == OpenState.OPEN && parking == OpenState.OPEN) OpenState.OPEN else OpenState.UNKNOWN
            val vacancy = pair.vacancy?.takeIf { it.fresh(now) }?.spaces
            val ttt = pair.timing?.total(now, origin)
            val arrival = ttt?.let { at.plusSeconds(it.toLong()) }
            if (!pair.eligiblePublicToilet || distance > options.radiusMeters || open == OpenState.CLOSED || vacancy == 0 ||
                (arrival != null && pair.toiletHours.state(arrival) == OpenState.CLOSED) ||
                (options.requireAccessible && pair.accessibleToilet != true) || (!options.includeUnknownHours && open == OpenState.UNKNOWN)) return@mapNotNull null
            val warnings = buildList {
                if (open == OpenState.UNKNOWN) add("운영시간 확인 필요")
                if (vacancy == null) add("빈자리 미확인")
                if (pair.walkingMeters == null) add("도보 동선 미확인")
                if (!pair.coordinatesVerified) add("시설 좌표 확인 필요")
                if (pair.sourceDate.isBefore(at.toLocalDate().minusDays(180))) add("오래된 시설 정보")
                if (pair.sourceDate.isAfter(at.toLocalDate())) add("정보 기준일 확인 필요")
            }
            Candidate(pair, distance, open, vacancy, warnings, ttt)
        }.sortedWith(compareBy<Candidate> { if (it.open == OpenState.OPEN) 0 else 1 }
            .thenBy { if (options.priority == Priority.FASTEST_TOILET) it.tttSeconds ?: Int.MAX_VALUE else 0 }
            .thenBy { if (options.priority == Priority.LESS_WALKING) it.pair.walkingMeters ?: Int.MAX_VALUE else 0 }
            .thenBy { it.straightMeters }.thenBy { it.pair.id }).take(3)
        return SearchResult(candidates, pairs.size - candidates.size, if (candidates.isEmpty()) "조건에 맞는 시설이 없어요. 검색 범위나 조건을 바꿔 주세요." else if(options.priority == Priority.FASTEST_TOILET) "운영시간 확인 여부별 TTT 추정 순서예요. 미확인 후보는 뒤에 표시해요." else "운영시간과 거리를 기준으로 고른 후보예요.")
    }
}
