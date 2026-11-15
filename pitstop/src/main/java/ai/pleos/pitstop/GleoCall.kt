package ai.pleos.pitstop

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Official Gleo Intent / broadcast adapter, independent of any microphone API. */
class GleoCall(private val context: Context, private val incoming: Intent) {
    val action: String = incoming.action.orEmpty()
    private val answered = AtomicBoolean(false)
    private val receivedAt = SystemClock.elapsedRealtime()
    private val main = Handler(Looper.getMainLooper())
    private val token = if (BuildConfig.DEBUG) incoming.getStringExtra("gleo_test_token")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) }.orEmpty() else ""
    private val timeout = Runnable { reply(GleoResult("fail", "The operation has not been confirmed in time. Check the app status before retrying.")) }
    init { Log.i("GleoInterface", "RECEIVED $action token=$token") }
    fun startDeadline() { main.postDelayed(timeout, 6_000) }
    fun canBegin(): Boolean = !answered.get() && SystemClock.elapsedRealtime() - receivedAt < 5_500
    @Suppress("DEPRECATION")
    fun parameters(names: Set<String>): GleoParameters {
        val extras = incoming.extras
        val raw = extras?.get("ACTION_PARAM")
        if (raw != null && raw !is String) throw GleoInput("asking", "Please provide valid command parameters.")
        val flat = names.filter { extras?.containsKey(it) == true }.associateWith { extras?.get(it) }
        return GleoParameters.parse(raw as? String, flat)
    }
    fun reply(result: GleoResult) {
        if (!answered.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        runCatching {
            context.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                .putExtra("packageName", context.packageName).putExtra("response", result.json(action)))
        }.onFailure { Log.w("GleoInterface", "Callback broadcast failed") }
        // No raw utterance, coordinates, password, or facility notes in logs.
        Log.i("GleoInterface", "RESULT $action ${result.status} token=$token message=${result.message}")
    }
}
