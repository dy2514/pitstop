package ai.pleos.pitstop

import ai.pleos.pitstop.core.*
import org.json.*
import java.time.LocalDate

data class Catalog(val label: String, val demo: Boolean, val pairs: List<FacilityPair>, val origin: GeoPoint?)
object CatalogParser {
    fun parse(text: String): Catalog {
        require(text.toByteArray().size <= 1_000_000) { "시설 파일은 1MB 이하여야 해요." }
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 2) { "공중화장실 이용 조건을 확인한 버전 2 시설 파일이 필요해요." }
        fun rejectPrivateData(value: Any?) {
            when(value) {
                is JSONObject -> value.keys().forEach { key ->
                    require(key.lowercase().replace("_", "") !in setOf("password", "accesscode", "doorcode", "pin", "passcode", "visithistory", "usagehistory")) {
                        "비밀번호와 개인 이용 기록은 시설 파일에 포함할 수 없어요."
                    }
                    rejectPrivateData(value.opt(key))
                }
                is JSONArray -> (0 until value.length()).forEach { rejectPrivateData(value.opt(it)) }
                is String -> require(!Regex("비밀번호|출입\\s*암호|password|passcode", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
                    "비밀번호 안내는 포함할 수 없어요."
                }
            }
        }
        rejectPrivateData(root)
        val demo = root.getString("mode") == "demo"
        require(root.getString("mode") in listOf("demo","real"))
        val items = root.getJSONArray("pairs"); require(items.length() <= 2000)
        fun point(j:JSONObject) = GeoPoint(j.getDouble("latitude"),j.getDouble("longitude"))
        fun hours(j:JSONObject?):OpeningHours {
            if (j == null) return OpeningHours()
            val windows=j.optJSONArray("windows") ?: JSONArray()
            return OpeningHours(j.getBoolean("known"),(0 until windows.length()).map { i ->
                val w=windows.getJSONObject(i);val days=w.getJSONArray("days")
                DailyWindow((0 until days.length()).map{days.getInt(it)}.toSet(),w.getInt("startMinute"),w.getInt("endMinute"))
            },j.optJSONArray("closedDates")?.let{a->(0 until a.length()).map{LocalDate.parse(a.getString(it))}.toSet()} ?: emptySet())
        }
        val pairs=(0 until items.length()).map { i ->
            val j=items.getJSONObject(i)
            FacilityPair(j.getString("id"),j.getString("toiletName"),j.getString("parkingName"),j.getString("address"),point(j.getJSONObject("parkingPoint")),
                j.getBoolean("coordinatesVerified"),j.getBoolean("publicAccess"),if(j.isNull("accessibleToilet")) null else j.getBoolean("accessibleToilet"),
                hours(j.optJSONObject("toiletHours")),hours(j.optJSONObject("parkingHours")),if(j.isNull("walkingMeters")) null else j.getInt("walkingMeters"),
                j.getString("walkingNote"),j.getString("fee"),j.getString("source"),LocalDate.parse(j.getString("sourceDate")), timing=j.optJSONObject("timing")?.let { t ->
                    fun seconds(key: String): Int? = if(t.isNull(key)) null else t.getInt(key)
                    ToiletTiming(seconds("drive"), seconds("signal"), t.getBoolean("signalsIncluded"),
                        seconds("parking"), seconds("walk"), seconds("floors"), seconds("queue"),
                        t.getLong("observedAtMs"), t.getString("source"), t.optJSONObject("origin")?.let(::point))
                }, facilityKind=FacilityKind.valueOf(j.getString("facilityKind")),
                accessRestriction=AccessRestriction.valueOf(j.getString("accessRestriction")))
        }
        require(pairs.map{it.id}.distinct().size == pairs.size) { "시설 ID가 중복돼요." }
        return Catalog(root.getString("label"),demo,pairs,root.optJSONObject("origin")?.let(::point))
    }
}
