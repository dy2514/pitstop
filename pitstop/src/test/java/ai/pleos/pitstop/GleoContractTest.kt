package ai.pleos.pitstop

import ai.pleos.pitstop.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GleoContractTest {
    private fun parse(raw:String,action:String=PitStopVoiceCommand.FIND)=PitStopVoiceCommand.parse(GleoParameters.parse(raw),action)
    @Test fun emergencyCallNeedsNoScreenSelectionOrRequiredParameters() {
        assertEquals(PitStopVoiceCommand(PitStopVoiceCommand.FIND),parse("{}"))
    }
    @Test fun nextAndResumeAreIndependentVoiceCommands() {
        assertTrue(parse("{}",PitStopVoiceCommand.NEXT).alternative)
        assertEquals(PitStopVoiceCommand.RESUME,parse("{}",PitStopVoiceCommand.RESUME).action)
    }
    @Test fun typedFlatParametersWork() {
        assertTrue(PitStopVoiceCommand.parse(GleoParameters.parse(null,mapOf("accessible" to true))).accessible)
    }
    @Test fun encodedJsonPayloadWorks() {
        assertTrue(parse(JSONObject.quote("""{"accessible":true}""")).accessible)
    }
    @Test fun removedPrivacyFlagAndMalformedParametersAreRejected() {
        listOf("""{"discreet":true}""","""{"accessible":1}""","""{"alternative":[]}""","""{"destination":"unverified"}""").forEach {
            try{parse(it);fail("Invalid request accepted")}catch(e:GleoInput){assertEquals("asking",e.status)}
        }
    }
    @Test fun nextAndResumeDoNotAcceptDestinationInjection() {
        listOf(PitStopVoiceCommand.NEXT,PitStopVoiceCommand.RESUME).forEach { action ->
            assertThrows(GleoInput::class.java){parse("""{"alternative":true}""",action)}
        }
    }
    @Test fun navigationRequestDoesNotClaimConfirmationOrInventTtt() {
        val pair=CatalogParser.parse(File("src/main/assets/catalog.json").readText()).pairs.first()
        val result=parse("{}").requested(Candidate(pair,100.0,OpenState.UNKNOWN,null,emptyList()))
        val data=JSONArray(JSONObject(result.json(PitStopVoiceCommand.FIND)).getString("data")).getJSONObject(0)
        assertEquals("requested",data.getString("navigationStatus"))
        assertFalse(data.getBoolean("timeToToiletEstimateAvailable"))
        assertFalse(data.has("estimatedTimeToToiletSeconds"))
        assertTrue(result.message.contains("pending"))
    }
    @Test fun schemaAndManifestExposeExactlyThreeCommands() {
        val schema=JSONObject(File("src/main/assets/schema.json").readText())
        assertEquals("ai.pleos.pitstop",schema.getString("packageName"))
        val functions=schema.getJSONArray("actionCalls");assertEquals(3,functions.length())
        val names=(0 until functions.length()).map { i ->
            val f=functions.getJSONObject(i).getJSONObject("function")
            assertEquals(0,f.getJSONObject("parameters").getJSONArray("required").length())
            f.getString("name")
        }.toSet()
        assertEquals(PitStopVoiceCommand.actions,names)
        val manifest=File("src/main/AndroidManifest.xml").readText()
        names.forEach { assertTrue(manifest.contains(it)) }
        assertFalse(schema.toString().contains("discreet"))
    }
}
