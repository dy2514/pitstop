package ai.pleos.pitstop

import ai.pleos.pitstop.core.*
import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class PleosNavigator(context: Context, private val session: DetourSession,
    private val location: (GeoPoint, Long) -> Unit, private val status: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val client = NaviHelper(context.applicationContext)
    private var running = false
    private var connected = false
    private var pending: RouteConfirmation? = null
    private var snapshot: CompletableDeferred<NavigationDestination?>? = null
    private var awaitingDestination = false
    private var capturedOption = RouteOption.RECOMMENDED
    var pendingDestinationId: String? = null
        private set
    val busy: Boolean get() = pending != null || snapshot != null
    private fun dispatch(action: () -> Unit) { main.post { if(running) action() } }
    private fun failure(message: String) {
        snapshot?.completeExceptionally(IllegalStateException(message))
        pending = null; pendingDestinationId = null; main.removeCallbacks(timeout)
        status(message)
    }
    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = dispatch {
            runCatching { GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude) }.onSuccess {
                if(it.latitude != 0.0 || it.longitude != 0.0) location(it, System.currentTimeMillis())
            }
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = dispatch {
            val wait = snapshot ?: return@dispatch
            if(awaitingDestination || wait.isCompleted) return@dispatch
            when(routeStateInfo.naviMode) {
                NaviMode.SAFE_GUIDING -> wait.complete(null)
                NaviMode.ROUTE_GUIDING -> {
                    capturedOption = routeStateInfo.routeOption
                    awaitingDestination = true
                    runCatching { client.getDestinationInfo() }.onFailure { wait.completeExceptionally(it) }
                }
                else -> wait.completeExceptionally(IllegalStateException("기본 내비를 실행하고 경로 상태를 확인해 주세요."))
            }
        }
        override fun onRoutingCompleted() = dispatch {
            pending?.routingCompleted()
            if(pending != null) runCatching { client.getDestinationInfo() }
                .onFailure { failure("내비 목적지를 확인하지 못했어요. 원래 목적지 정보는 유지해요.") }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = dispatch {
            val point = runCatching { GeoPoint(destinationInfo.latitude, destinationInfo.longitude) }.getOrNull()
            val wait = snapshot
            if(wait != null && awaitingDestination && !wait.isCompleted) {
                if(point == null || (point.latitude == 0.0 && point.longitude == 0.0))
                    wait.completeExceptionally(IllegalStateException("원래 목적지 좌표를 확인하지 못했어요."))
                else wait.complete(NavigationDestination(point, destinationInfo.name, destinationInfo.address,
                    destinationInfo.poiId.orEmpty(), destinationInfo.poiSubId ?: "0", destinationInfo.poiProvider.orEmpty(), capturedOption.name))
                return@dispatch
            }
            val request = pending ?: return@dispatch
            if(point != null && request.matches(point)) {
                if(request.restoring) session.resumeConfirmed()
                pending = null; pendingDestinationId = null; main.removeCallbacks(timeout)
                status(if(request.restoring) "원래 목적지 안내 확인 완료" else "공중화장실 주차장 안내 확인 완료 · ${request.target.name}")
            }
        }
        override fun onDestinationChanged() = dispatch {
            // Do not capture a destination that changed during the snapshot request.
            snapshot?.takeIf { !it.isCompleted }?.completeExceptionally(IllegalStateException("내비 목적지가 변경됐어요. 다시 요청해 주세요."))
        }
        override fun onError(errorCode: NaviErrorCode) = dispatch {
            failure("내비 요청을 처리하지 못했어요 · $errorCode. 원래 목적지 정보는 유지해요.")
        }
    }
    private val timeout = Runnable {
        if(pending != null) failure("내비 확인 시간이 초과됐어요. 기본 내비를 확인해 주세요. 원래 목적지 정보는 유지해요.")
    }
    private val poll = object : Runnable { override fun run() {
        if(!running) return
        if(connected) runCatching { client.getCurrentLocationInfo() }
        main.postDelayed(this, 5_000)
    } }
    fun start() {
        if(running) return
        running = true
        runCatching { client.initialize(onConnected={dispatch {
            connected=true; client.addListener(listener); main.post(poll); status("차량 위치 연결됨")
        }}, onFailed={dispatch { status("내비 연결을 확인해 주세요. 예제 시연은 사용할 수 있어요.") }}) }
            .onFailure { status("내비 연결을 확인해 주세요.") }
    }
    private suspend fun rememberOriginal() {
        if(session.originalCaptured) return
        val wait = CompletableDeferred<NavigationDestination?>()
        snapshot=wait; awaitingDestination=false
        try {
            client.getRouteStateInfo()
            val destination = withTimeout(3_000) { wait.await() }
            session.rememberOriginal(destination)
        } finally { snapshot=null; awaitingDestination=false }
    }
    suspend fun route(pair: FacilityPair) {
        check(running && connected) { "내비 연결을 확인해 주세요." }
        check(!busy) { "앞선 내비 요청을 확인하고 있어요." }
        check(pair.coordinatesVerified && pair.eligiblePublicToilet && pair.id !in session.excludedIds) {
            "이용 조건과 좌표가 확인된 공중화장실만 안내할 수 있어요."
        }
        rememberOriginal()
        val destination = NavigationDestination(pair.parkingPoint, pair.parkingName, pair.address)
        issue(destination, false, pair.id)
        session.stopRequested(pair)
    }
    fun resumeOriginal() {
        check(running && connected) { "내비 연결을 확인해 주세요." }
        check(!busy) { "앞선 내비 요청을 확인하고 있어요." }
        val destination = session.original ?: error("기억한 원래 목적지가 없어요. 기본 내비에서 목적지를 설정해 주세요.")
        issue(destination, true, null)
    }
    private fun issue(destination: NavigationDestination, restoring: Boolean, id: String?) {
        val request = RouteConfirmation(destination, restoring)
        pending=request; pendingDestinationId=id
        try {
            client.requestRoute(RouteInfo(latitude=destination.point.latitude, longitude=destination.point.longitude,
                displayLatitude=null, displayLongitude=null, poiName=destination.name, poiId=destination.poiId,
                poiSubId=destination.poiSubId, poiProvider=destination.poiProvider, address=destination.address,
                routeOption=runCatching { RouteOption.valueOf(destination.routeOption) }.getOrDefault(RouteOption.RECOMMENDED)))
            status(if(restoring) "원래 목적지로 재탐색 요청 · 결과 확인 중" else "공중화장실 안내 요청 · 결과 확인 중")
            main.postDelayed(timeout, 20_000)
        } catch(e: Exception) { pending=null; pendingDestinationId=null; throw e }
    }
    fun close() {
        running=false; connected=false; pending=null; pendingDestinationId=null
        snapshot?.cancel(); snapshot=null; main.removeCallbacksAndMessages(null)
        runCatching { client.removeListener(listener) }; runCatching { client.release() }
    }
}
