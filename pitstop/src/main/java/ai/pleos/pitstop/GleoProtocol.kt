package ai.pleos.pitstop

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Gleo owns speech recognition; this boundary only accepts structured function calls. */
class GleoInput(val status: String, override val message: String) : IllegalArgumentException(message)

class GleoParameters private constructor(private val values: JSONObject) {
    fun only(vararg names: String) {
        if (values.keys().asSequence().any { it !in names }) invalid()
    }
    fun text(name: String, choices: Set<String>): String {
        if (!values.has(name)) throw GleoInput("asking", "Please specify $name.")
        return (values.opt(name) as? String)?.takeIf { it in choices } ?: invalid()
    }
    fun seconds(name: String): Long {
        if (!values.has(name)) throw GleoInput("asking", "Please specify a duration of one, three, or five minutes.")
        val value = (values.opt(name) as? Number)?.toDouble() ?: invalid()
        if (value !in setOf(60.0, 180.0, 300.0)) invalid()
        return value.toLong()
    }
    fun flag(name: String, default: Boolean? = null): Boolean {
        if (!values.has(name)) return default ?: throw GleoInput("asking", "Please specify $name.")
        return values.opt(name) as? Boolean ?: invalid()
    }
    companion object {
        private fun invalid(): Nothing = throw GleoInput("asking", "Please provide valid parameters for this command.")
        fun parse(raw: String?, flat: Map<String, Any?> = emptyMap()): GleoParameters {
            try {
                if (raw == null) return GleoParameters(JSONObject(flat))
                if (raw.length > 8192 || flat.isNotEmpty()) invalid()
                fun value(text: String): Any? {
                    val reader = JSONTokener(text)
                    val result = reader.nextValue()
                    if (reader.nextClean() != '\u0000') invalid()
                    return result
                }
                val first = value(raw)
                // The official guide describes both a JSON object and a JSON-encoded string.
                val obj = (if (first is String) value(first) else first) as? JSONObject ?: invalid()
                return GleoParameters(obj)
            } catch (e: GleoInput) { throw e }
            catch (_: Exception) { invalid() }
        }
    }
}

data class GleoResult(val status: String, val message: String, val data: List<Map<String, Any>> = emptyList(), val silent: Boolean = false) {
    fun json(function: String): String = JSONObject().apply {
        put("functionName", function); put("status", status); put("message", message)
        put("mode", if (silent) "silent" else "voice")
        if (data.isNotEmpty()) put("data", JSONArray(data.map { JSONObject(it) }).toString())
    }.toString()
}
