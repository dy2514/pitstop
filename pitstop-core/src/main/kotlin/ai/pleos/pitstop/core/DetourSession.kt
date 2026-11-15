package ai.pleos.pitstop.core

/** Volatile journey state only. Never serialized to preferences, files or usage analytics. */
data class NavigationDestination(val point: GeoPoint, val name: String, val address: String = "",
    val poiId: String = "", val poiSubId: String = "0", val poiProvider: String = "",
    val routeOption: String = "RECOMMENDED")

class DetourSession {
    var originalCaptured: Boolean = false
        private set
    var original: NavigationDestination? = null
        private set
    var currentStop: FacilityPair? = null
        private set
    private val unavailable = linkedSetOf<String>()
    val excludedIds: Set<String> get() = unavailable.toSet()

    fun rememberOriginal(destination: NavigationDestination?) {
        if (originalCaptured) return
        original = destination
        originalCaptured = true
    }
    fun stopRequested(pair: FacilityPair) {
        check(originalCaptured) { "원래 목적지 확인이 먼저 필요해요." }
        require(pair.eligiblePublicToilet && pair.coordinatesVerified)
        check(pair.id !in unavailable) { "이번 탐색에서 이용 불가로 표시한 시설이에요." }
        currentStop = pair
    }
    fun markUnavailable(): String {
        val id = currentStop?.id ?: error("먼저 안내 중인 공중화장실이 있어야 해요.")
        unavailable += id
        return id
    }
    fun exclude(id: String) { unavailable += id }
    fun search(origin: GeoPoint, fixAtMs: Long, pairs: List<FacilityPair>, at: java.time.ZonedDateTime,
        options: SearchOptions = SearchOptions(Priority.FASTEST_TOILET)): SearchResult =
        PitStopEngine.search(origin, fixAtMs, pairs.filter { it.id !in unavailable }, at, options)

    /** Call only after routing completion AND destination-coordinate confirmation. */
    fun resumeConfirmed() { clear() }
    fun clear() { originalCaptured = false; original = null; currentStop = null; unavailable.clear() }
}

/** A request accepted by the SDK does not establish that navigation changed. */
class RouteConfirmation(val target: NavigationDestination, val restoring: Boolean) {
    private var completed = false
    fun routingCompleted() { completed = true }
    fun matches(destination: GeoPoint): Boolean = completed && target.point.metersTo(destination) < 50.0
}
