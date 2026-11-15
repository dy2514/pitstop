package ai.pleos.pitstop.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PitStopEngineTest {
    private val now = ZonedDateTime.of(2026,11,15,14,0,0,0,ZoneId.of("Asia/Seoul"))
    private val origin = GeoPoint(37.28,127.01)
    private val all = OpeningHours(true,listOf(DailyWindow((1..7).toSet(),0,1440)))
    private fun pair(id:String="a", lat:Double=37.281, walk:Int?=100) = FacilityPair(id,"화장실 $id","주차장 $id","주소",GeoPoint(lat,127.01),true,true,true,all,all,walk,"도보","유료","test fixture",now.toLocalDate(),facilityKind=FacilityKind.PUBLIC_TOILET,accessRestriction=AccessRestriction.NONE)
    private fun search(p:List<FacilityPair>, options:SearchOptions=SearchOptions(), age:Long=0) = PitStopEngine.search(origin,now.toInstant().toEpochMilli()-age,p,now,options)
    @Test fun closedToiletIsExcluded() { assertTrue(search(listOf(pair().copy(toiletHours=OpeningHours(true)))).candidates.isEmpty()) }
    @Test fun closedParkingIsExcludedEvenWithOpenToilet() { assertTrue(search(listOf(pair().copy(parkingHours=OpeningHours(true)))).candidates.isEmpty()) }
    @Test fun restrictedParkingNeverRecommended() { assertTrue(search(listOf(pair().copy(publicAccess=false))).candidates.isEmpty()) }
    @Test fun freshFullParkingIsExcluded() { assertTrue(search(listOf(pair().copy(vacancy=Vacancy(0,now.toInstant().toEpochMilli())))).candidates.isEmpty()) }
    @Test fun staleVacancyBecomesUnknown() { val r=search(listOf(pair().copy(vacancy=Vacancy(12,now.toInstant().toEpochMilli()-121000)))); assertNull(r.candidates.single().vacancy);assertTrue("빈자리 미확인" in r.candidates.single().warnings) }
    @Test fun futureVacancyIsNotTrusted() { assertNull(search(listOf(pair().copy(vacancy=Vacancy(2,now.toInstant().toEpochMilli()+1000)))).candidates.single().vacancy) }
    @Test fun stalePositionCannotProduceSuggestions() { assertTrue(search(listOf(pair()),age=31000).candidates.isEmpty()) }
    @Test fun accessibleUnknownDoesNotPassAccessibleFilter() { assertTrue(search(listOf(pair().copy(accessibleToilet=null)),SearchOptions(requireAccessible=true)).candidates.isEmpty()) }
    @Test fun walkingPriorityMayBeatNearestParking() { val r=search(listOf(pair("near",37.281,400),pair("walk",37.284,50)),SearchOptions(priority=Priority.LESS_WALKING));assertEquals("walk",r.candidates.first().pair.id) }
    @Test fun unknownHoursAreNotPresentedAsOpen() { val p=pair().copy(toiletHours=OpeningHours());assertEquals(OpenState.UNKNOWN,search(listOf(p)).candidates.single().open);assertTrue(search(listOf(p),SearchOptions(includeUnknownHours=false)).candidates.isEmpty()) }
    @Test fun overnightUsesPreviousOpeningDay() { val h=OpeningHours(true,listOf(DailyWindow(setOf(6),22*60,2*60)));assertEquals(OpenState.OPEN,h.state(now.withHour(1)));assertEquals(OpenState.CLOSED,h.state(now.withHour(3))) }
    @Test fun exceptionalClosureWins() { assertEquals(OpenState.CLOSED,all.copy(closedDates=setOf(now.toLocalDate())).state(now)) }
    @Test fun maxThreeAndStableTieOrdering() { val r=search(listOf(pair("d"),pair("c"),pair("b"),pair("a")));assertEquals(listOf("a","b","c"),r.candidates.map{it.pair.id}) }
    @Test fun unverifiedCoordinatesRemainExplicit() { assertTrue("시설 좌표 확인 필요" in search(listOf(pair().copy(coordinatesVerified=false))).candidates.single().warnings) }
    @Test fun radiusAndInvalidCoordinates() { assertTrue(search(listOf(pair(lat=38.0))).candidates.isEmpty());assertThrows(IllegalArgumentException::class.java){GeoPoint(Double.NaN,127.0)} }
}
