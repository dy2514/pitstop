package ai.pleos.pitstop

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogTest {
    private fun asset(name:String)=File("src/main/assets/$name").readText()
    @Test fun bundledRealCatalogHasCitedFacilitiesAndNoInventedOccupancyOrWalkLengths() {
        val c=CatalogParser.parse(asset("catalog.json"));assertFalse(c.demo);assertEquals(4,c.pairs.size)
        c.pairs.forEach{assertTrue(it.eligiblePublicToilet);assertTrue(it.source.isNotBlank());assertNull(it.vacancy);assertNull(it.walkingMeters);assertFalse(it.toiletHours.known)}
    }
    @Test fun demonstrationIsExplicitAndCannotMasqueradeAsVerifiedCoordinates(){
        val c=CatalogParser.parse(asset("demo.json"));assertTrue(c.demo);assertNotNull(c.origin);assertTrue(c.pairs.all{!it.coordinatesVerified})
    }
    @Test fun unsupportedSchemaIsRejected(){assertThrows(IllegalArgumentException::class.java){CatalogParser.parse(asset("catalog.json").replace("\"schemaVersion\": 2","\"schemaVersion\": 9"))}}
    @Test fun duplicateIdsAreRejected(){
        val root=org.json.JSONObject(asset("catalog.json"));val a=root.getJSONArray("pairs");a.put(a.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java){CatalogParser.parse(root.toString())}
    }
    @Test fun oversizeImportIsRejectedBeforeParsing(){assertThrows(IllegalArgumentException::class.java){CatalogParser.parse("x".repeat(1_000_001))}}

    @Test fun credentialsAndVisitRecordsAreRejectedBeforePersistence() {
        listOf("password","accessCode","door_code","visitHistory").forEach { key ->
            val root=org.json.JSONObject(asset("catalog.json"))
            root.getJSONArray("pairs").getJSONObject(0).put(key,"private value")
            assertThrows(IllegalArgumentException::class.java){CatalogParser.parse(root.toString())}
        }
    }
    @Test fun passwordInEntranceNotesIsRejected() {
        val root=org.json.JSONObject(asset("catalog.json"))
        root.getJSONArray("pairs").getJSONObject(0).put("walkingNote","비밀번호 1234")
        assertThrows(IllegalArgumentException::class.java){CatalogParser.parse(root.toString())}
    }
    @Test fun legacyCatalogCannotAssumePublicUnrestrictedAccess() {
        val root=org.json.JSONObject(asset("catalog.json")).put("schemaVersion",1)
        assertThrows(IllegalArgumentException::class.java){CatalogParser.parse(root.toString())}
    }
}
