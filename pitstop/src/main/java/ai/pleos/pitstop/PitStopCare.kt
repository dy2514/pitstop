package ai.pleos.pitstop

import android.content.Context
import kotlinx.coroutines.*

/** Foreground-only opt-in comfort; rollback is conditional on our confirmed values. */
class PitStopCare(context: Context, private val status: (String) -> Unit) {
    private data class Owned(val port: TemporaryControl, val before: Settings, var expected: Settings,
        var relinquished: Boolean = false, var writing: Boolean = false)
    private val owned = linkedMapOf<String, Owned>()
    private val hardware = AccessoryControls(context) { id, key, value ->
        owned[id]?.let { if (!it.writing && (value == null || it.expected[key] != value)) it.relinquished = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var stopped = false
    fun start() {
        check(work == null)
        work = scope.launch {
            var detail = "수동 변경 유지"
            try {
                status("차량 온열 지원 확인 중")
                hardware.discover()
                ensureActive()
                for (id in listOf("seatHeat", "wheel")) {
                    val port = hardware.ports[id] ?: continue
                    val before = port.read()
                    val target = if(id == "wheel") mapOf("value" to hardware.maxWheelHeat.toString())
                        else if("heat" in before) mapOf("heat" to minOf(3, hardware.maxSeatHeat).toString(), "vent" to "0")
                        else mapOf("value" to minOf(3, hardware.maxSeatHeat).toString())
                    if(target == before) continue
                    val entry = Owned(port,before,before,writing=true)
                    owned[id] = entry
                    try { port.writeTracked(target,before) { entry.expected = it.toMap() } }
                    finally { entry.writing=false }
                    check(port.read() == target) { "온열 적용 확인 실패" }
                }
                if(owned.isEmpty()) { detail="지원되는 변경 항목이 없어요."; return@launch }
                status("선택형 온열 적용 · 최대 3분, 화면을 나가면 복원")
                repeat(180) {
                    delay(1000)
                    owned.values.filter { !it.relinquished }.forEach { entry ->
                        if(runCatching { entry.port.read() }.getOrNull() != entry.expected) entry.relinquished=true
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { detail="제어 중단: ${e.message.orEmpty().take(80)}"; status(detail) }
            finally {
                withContext(NonCancellable) {
                    var failed = false
                    for(entry in owned.values.toList().asReversed()) {
                        if(entry.relinquished) continue
                        try {
                            if(entry.port.read() == entry.expected) {
                                entry.writing=true
                                entry.port.writeTracked(entry.before,entry.expected) { entry.expected=it.toMap() }
                                if(!entry.port.restoredMatches(entry.before,entry.port.read())) failed=true
                            }
                        } catch(_: Exception) { failed=true }
                        finally { entry.writing=false }
                    }
                    hardware.close()
                    status(if(failed) "일부 복원 확인 실패 · 차량 온열 설정을 확인해 주세요." else "온열 종료 · $detail")
                }
                scope.cancel()
            }
        }
    }
    fun stop() { if(!stopped) { stopped=true; work?.cancel() } }
}
