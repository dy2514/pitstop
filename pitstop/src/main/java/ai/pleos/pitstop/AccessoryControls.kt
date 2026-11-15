package ai.pleos.pitstop

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ControlInfo(val id: String, val title: String, val description: String, val available: Boolean,
    val current: String = "", val reason: String = "", val phase: String = "")

class AccessoryControls(context: Context, private val event: (String, String, String?) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private val cleanup = mutableListOf<() -> Unit>()
    val ports = linkedMapOf<String, TemporaryControl>()
    val info = mutableListOf<ControlInfo>()
    var maxSeatHeat = 0
        private set
    var minSeatHeat = 0
        private set
    var maxSeatVent = 0
        private set
    var maxWheelHeat = 0
        private set
    private suspend fun <T : Any> request(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T = withTimeout(4_000) {
        suspendCancellableCoroutine { c ->
            try { call({ value -> if (c.isActive) { if (value != null) c.resume(value) else c.resumeWithException(IllegalStateException("차량 값이 비어 있어요.")) } }, { if (c.isActive) c.resumeWithException(it) }) }
            catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
        }
    }
    private fun changed(id: String, key: String, value: Any?) { main.post { if (!closed) event(id, key, value?.toString()) } }
    private fun writable(config: CapabilityConfig) { check(config.accessType.name == "READ_WRITE") { "이 차량에서는 읽기·쓰기 권한을 지원하지 않아요." } }
    private fun area(bounds: Map<SeatArea, AreaBounds<Int>>): SeatArea = bounds.keys
        .filter { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) }.firstOrNull()
        ?: throw IllegalStateException("운전석 개별 제어를 지원하지 않아요.")

    suspend fun discover() {
        vehicle.initialize()
        // Optional features fail independently: no fallback values and no privileged permission grants.
        // No public Pleos API currently exposes a media-only volume group setter.
        // AudioManager.STREAM_MUSIC cannot prove separation from navigation on AAOS.
        // Do not use master mute, volume key injection, audio focus theft, or privileged APIs.
        info += ControlInfo("media", "음악·영상만 잠깐 무음", "필수 안내 음성 유지", false,
            reason = "미디어 전용 음량 제어가 제공되지 않아 사용할 수 없어요.")
        add("wheel", "손 따뜻하게", "핸들 열선 낮은 단계로") {
            val api = vehicle.getSteeringWheel()
            val config = request<Pair<CapabilityConfig, AreaBounds<Int>>> { ok, fail -> api.checkHvacSteeringWheelHeatCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = fail) }
            writable(config.first); check(config.second.maxValue >= 1)
            maxWheelHeat = config.second.maxValue
            val listener = object : HvacSteeringWheelHeatStateListener {
                override fun onHvacSteeringWheelHeatStateUpdated(value: Int?) = changed("wheel", "value", value)
                override fun onFailed(e: Exception) = changed("wheel", "value", null)
            }
            api.registerHvacSteeringWheelHeatState(listener); cleanup += { api.unregisterHvacSteeringWheelHeatState(listener) }
            IntegerControl("wheel", "손 따뜻하게", "핸들 열선 낮은 단계로",
                { request<Int> { ok, fail -> api.getHvacSteeringWheelHeatState(onComplete = ok, onFailed = fail) } },
                { value -> request<Int> { ok, fail -> api.setHvacSteeringWheelHeat(value, onComplete = ok, onFailed = fail) }; Unit }, { 1.coerceIn(config.second.minValue, config.second.maxValue) })
        }
        add("ambient", "조명 은은하게", "실내 무드등 밝기를 낮춰요") {
            val api = vehicle.getLight()
            val config = request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail -> api.checkAmbientLightBrightnessCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = fail) }
            writable(config.first)
            val a = config.second.keys.filter { SeatValue.ROW_1_LEFT in it.values }.minByOrNull { it.values.size }
                ?: error("무드등 밝기 조절을 지원하지 않아요.")
            val b = config.second.getValue(a)
            val listener = object : AmbientLightBrightnessListener {
                override fun onBrightnessUpdated(value: Int?) = changed("ambient", "value", value)
                override fun onFailed(e: Exception) = changed("ambient", "value", null)
            }
            api.registerAmbientLightBrightnessListener(a, listener); cleanup += { api.unregisterAmbientLightBrightnessListener(a, listener) }
            IntegerControl("ambient", "조명 은은하게", "실내 무드등 밝기를 낮춰요",
                { request<Int> { ok, fail -> api.getAmbientLightBrightness(a, onComplete = ok, onFailed = fail) } },
                { value -> request<Int> { ok, fail -> api.setAmbientLightBrightness(a, value, onComplete = { _, v -> ok(v) }, onFailed = fail) }; Unit },
                { old -> (old / 3).coerceIn(b.minValue, b.maxValue) }, { "밝기 $it" })
        }
        try { discoverSeat() } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            listOf("seatHeat" to "시트 따뜻하게", "seatCool" to "시트 시원하게").forEach { (id, title) ->
                info += ControlInfo(id, title, "운전석 좌석", false, reason = e.message.orEmpty().take(120))
            }
        }
    }

    private suspend fun add(id: String, title: String, description: String, build: suspend () -> TemporaryControl) {
        try {
            val port = build(); val value = port.read()
            ports[id] = port; info += ControlInfo(id, title, description, true, port.format(value))
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            info += ControlInfo(id, title, description, false, reason = e.message.orEmpty().take(120))
        }
    }

    private suspend fun discoverSeat() {
        val api = vehicle.getSeat()
        val heatConfig = request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail -> api.checkSeatTemperatureCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = fail) }
        writable(heatConfig.first)
        val a = area(heatConfig.second)
        maxSeatHeat = heatConfig.second.getValue(a).maxValue
        minSeatHeat = heatConfig.second.getValue(a).minValue
        check(maxSeatHeat >= 1 && minSeatHeat <= 0)
        val ventConfig = request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail -> api.checkSeatVentilationCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = fail) }
        if (minSeatHeat < 0 && ventConfig.first.accessType.name == "NONE" && ventConfig.second.isEmpty()) {
            // This vehicle explicitly has one signed temperature property and no separate ventilation actuator.
            val listener = object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(value: Int?) { changed("seatHeat", "value", value); changed("seatCool", "value", value) }
                override fun onFailed(e: Exception) { changed("seatHeat", "value", null); changed("seatCool", "value", null) }
            }
            api.registerHvacSeatTemperature(a, listener); cleanup += { api.unregisterHvacSeatTemperature(a, listener) }
            for (heat in listOf(true, false)) {
                val id = if (heat) "seatHeat" else "seatCool"
                val title = if (heat) "시트 따뜻하게" else "시트 시원하게"
                add(id, title, "운전석 ${if (heat) "열선" else "통풍"} 1단계") {
                    IntegerControl(id, title, "운전석 ${if (heat) "열선" else "통풍"} 1단계",
                        { request<Int> { ok, fail -> api.getHvacSeatTemperature(a, onComplete = ok, onFailed = fail) }.also { check(it in minSeatHeat..maxSeatHeat) } },
                        { value ->
                            require(value in minSeatHeat..maxSeatHeat)
                            val actual = request<Int> { ok, fail -> api.setHvacSeatTemperature(a, value, onComplete = { _, v -> ok(v) }, onFailed = fail) }
                            check(actual == value)
                        }, { if (heat) 1 else -1 },
                        { value -> if (value < 0) "통풍 ${-value}" else "열선 $value" })
                }
            }
            return
        }
        writable(heatConfig.first); writable(ventConfig.first)
        val b = area(ventConfig.second)
        check(heatConfig.second.getValue(a).maxValue >= 1 && ventConfig.second.getValue(b).maxValue >= 1)
        check(heatConfig.second.getValue(a).minValue <= 0 && ventConfig.second.getValue(b).minValue <= 0)
        maxSeatHeat = heatConfig.second.getValue(a).maxValue
        minSeatHeat = heatConfig.second.getValue(a).minValue
        maxSeatVent = ventConfig.second.getValue(b).maxValue
        val heatListener = object : HvacSeatTemperatureListener {
            override fun onHvacSeatTemperatureUpdated(value: Int?) { changed("seatHeat", "heat", value); changed("seatCool", "heat", value) }
            override fun onFailed(e: Exception) { changed("seatHeat", "heat", null); changed("seatCool", "heat", null) }
        }
        val ventListener = object : HvacSeatVentilationListener {
            override fun onHvacSeatVentilationUpdated(value: Int?) { changed("seatHeat", "vent", value); changed("seatCool", "vent", value) }
            override fun onFailed(e: Exception) { changed("seatHeat", "vent", null); changed("seatCool", "vent", null) }
        }
        api.registerHvacSeatTemperature(a, heatListener); cleanup += { api.unregisterHvacSeatTemperature(a, heatListener) }
        api.registerHvacSeatVentilation(b, ventListener); cleanup += { api.unregisterHvacSeatVentilation(b, ventListener) }
        for (heat in listOf(true, false)) {
            val id = if (heat) "seatHeat" else "seatCool"
            val title = if (heat) "시트 따뜻하게" else "시트 시원하게"
            add(id, title, "운전석 ${if (heat) "열선" else "통풍"} 1단계") {
                object : TemporaryControl {
                    override val id = id
                    override val title = title
                    override val description = "운전석 ${if (heat) "열선" else "통풍"} 1단계"
                    override suspend fun read(): Settings {
                        val temperature = request<Int> { ok, fail -> api.getHvacSeatTemperature(a, onComplete = ok, onFailed = fail) }
                        val ventilation = request<Int> { ok, fail -> api.getHvacSeatVentilationState(b, onComplete = ok, onFailed = fail) }
                        check(temperature in minSeatHeat..maxSeatHeat && ventilation in 0..maxSeatVent) { "좌석 단계 범위 확인 필요" }
                        check(temperature == 0 || ventilation == 0) { "좌석 온도와 별도 통풍이 함께 켜져 있어 현재 설정을 유지해요." }
                        return mapOf("heat" to temperature.toString(), "vent" to ventilation.toString())
                    }
                    override fun target(before: Settings): Settings = mapOf("heat" to if (heat) "1" else "0", "vent" to if (heat) "0" else "1")
                    override suspend fun write(values: Settings, expectedBefore: Settings) {
                        writeTracked(values, expectedBefore) {}
                    }
                    override suspend fun writeTracked(values: Settings, expectedBefore: Settings, confirmed: (Settings) -> Unit) {
                        check(read() == expectedBefore) { "직접 바뀐 좌석 설정을 유지해요." }
                        // Disable the opposing function first; never request heat and ventilation together.
                        val heatValue = values.getValue("heat").toInt(); val ventValue = values.getValue("vent").toInt()
                        check(heatValue == 0 || ventValue == 0) { "열선과 통풍을 동시에 적용할 수 없어요." }
                        require(heatValue in minSeatHeat..maxSeatHeat && ventValue in 0..maxSeatVent)
                        if (heatValue != 0) {
                            request<Int> { ok, fail -> api.setHvacSeatVentilation(b, ventValue, onComplete = { _, v -> ok(v) }, onFailed = fail) }
                            confirmed(expectedBefore + ("vent" to ventValue.toString()))
                            check(read() == expectedBefore + ("vent" to ventValue.toString())) { "좌석 설정이 바뀌어 추가 변경을 멈췄어요." }
                            request<Int> { ok, fail -> api.setHvacSeatTemperature(a, heatValue, onComplete = { _, v -> ok(v) }, onFailed = fail) }
                            confirmed(values)
                        } else {
                            request<Int> { ok, fail -> api.setHvacSeatTemperature(a, heatValue, onComplete = { _, v -> ok(v) }, onFailed = fail) }
                            confirmed(expectedBefore + ("heat" to heatValue.toString()))
                            check(read() == expectedBefore + ("heat" to heatValue.toString())) { "좌석 설정이 바뀌어 추가 변경을 멈췄어요." }
                            request<Int> { ok, fail -> api.setHvacSeatVentilation(b, ventValue, onComplete = { _, v -> ok(v) }, onFailed = fail) }
                            confirmed(values)
                        }
                    }
                    override fun format(values: Settings): String {
                        val temperature = values.getValue("heat").toInt()
                        return "열선 ${maxOf(0, temperature)} · 통풍 ${maxOf(-temperature, values.getValue("vent").toInt())}"
                    }
                }
            }
        }
    }

    fun close() { closed = true; cleanup.forEach { runCatching(it) }; cleanup.clear(); runCatching { vehicle.release() } }
}
