package ai.pleos.pitstop.core

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class DetourSessionTest {
    private val at=ZonedDateTime.parse("2026-11-15T14:00:00+09:00")
    private val origin=GeoPoint(37.28,127.01)
    private val hours=OpeningHours(true,listOf(DailyWindow((1..7).toSet(),0,1440)))
    private val home=NavigationDestination(GeoPoint(37.3,127.1),"원래 목적지")
    private fun pair(id:String,lat:Double=37.281)=FacilityPair(id,id,id,"주소",GeoPoint(lat,127.01),true,true,true,
        hours,hours,50,"1층 입구","유료","test",at.toLocalDate(),
        facilityKind=FacilityKind.PUBLIC_TOILET,accessRestriction=AccessRestriction.NONE)
    private fun search(s:DetourSession,pairs:List<FacilityPair>,point:GeoPoint=origin)=
        s.search(point,at.toInstant().toEpochMilli(),pairs,at)
    @Test fun accessRequiresExplicitPublicFacilityAndNoRestriction() {
        val p=pair("a")
        val variants=listOf(p.copy(facilityKind=FacilityKind.OTHER),p.copy(facilityKind=FacilityKind.UNKNOWN),
            p.copy(accessRestriction=AccessRestriction.CUSTOMER_ONLY),p.copy(accessRestriction=AccessRestriction.AUTHORIZATION_REQUIRED),
            p.copy(accessRestriction=AccessRestriction.UNKNOWN),p.copy(publicAccess=false))
        assertTrue(search(DetourSession(),variants).candidates.isEmpty())
        assertEquals(1,search(DetourSession(),listOf(p)).candidates.size)
    }
    @Test fun repeatedAlternativesDoNotCycleBackToClosedFacilities() {
        val s=DetourSession();s.rememberOriginal(home)
        val pairs=listOf(pair("a"),pair("b"),pair("c"))
        s.stopRequested(pairs[0]);s.markUnavailable()
        assertEquals(listOf("b","c"),search(s,pairs).candidates.map{it.pair.id})
        s.stopRequested(pairs[1]);s.markUnavailable()
        assertEquals(listOf("c"),search(s,pairs).candidates.map{it.pair.id})
        assertEquals(home,s.original)
    }
    @Test fun unavailableCandidatesRemainExcludedWhenNoneRemain() {
        val s=DetourSession();s.rememberOriginal(home);s.stopRequested(pair("a"));s.markUnavailable()
        assertTrue(search(s,listOf(pair("a"))).candidates.isEmpty())
        assertEquals(setOf("a"),s.excludedIds);assertEquals(home,s.original)
    }
    @Test fun noActiveStopCannotExcludeAnArbitraryCandidate() {
        assertThrows(IllegalStateException::class.java){DetourSession().markUnavailable()}
    }
    @Test fun firstSnapshotSurvivesAllLaterStops() {
        val s=DetourSession();s.rememberOriginal(home);s.stopRequested(pair("a"))
        s.rememberOriginal(NavigationDestination(pair("a").parkingPoint,"경유지"))
        assertEquals(home,s.original)
    }
    @Test fun noOriginalDestinationDoesNotBecomeFirstToilet() {
        val s=DetourSession();s.rememberOriginal(null);s.stopRequested(pair("a"));s.rememberOriginal(home)
        assertNull(s.original);assertTrue(s.originalCaptured)
    }
    @Test fun alternativeUsesNewOriginAndRejectsOldTtt() {
        val s=DetourSession()
        val p=pair("a").copy(timing=ToiletTiming(60,0,true,10,20,0,0,at.toInstant().toEpochMilli(),"test",origin))
        assertEquals(90,search(s,listOf(p)).candidates.single().tttSeconds)
        assertNull(search(s,listOf(p),GeoPoint(37.285,127.01)).candidates.single().tttSeconds)
    }
    @Test fun routeAcceptanceAloneDoesNotClearReturnDestination() {
        val s=DetourSession();s.rememberOriginal(home);s.stopRequested(pair("a"))
        val confirmation=RouteConfirmation(home,true)
        assertFalse(confirmation.matches(home.point));assertEquals(home,s.original)
        confirmation.routingCompleted()
        assertFalse(confirmation.matches(pair("a").parkingPoint));assertEquals(home,s.original)
        assertTrue(confirmation.matches(home.point))
        s.resumeConfirmed();assertFalse(s.originalCaptured);assertNull(s.currentStop);assertNull(s.original)
    }
    @Test fun exclusionsAreOnlySessionLocal() {
        val s=DetourSession();s.exclude("a");assertTrue(DetourSession().excludedIds.isEmpty())
        s.clear();assertTrue(s.excludedIds.isEmpty())
    }
    @Test fun unverifiedCoordinatesCannotBeRouted() {
        val s=DetourSession();s.rememberOriginal(null)
        assertThrows(IllegalArgumentException::class.java){s.stopRequested(pair("a").copy(coordinatesVerified=false))}
    }
    @Test fun toiletClosingBeforeEstimatedArrivalIsExcluded() {
        val p=pair("closing").copy(toiletHours=OpeningHours(true,listOf(DailyWindow((1..7).toSet(),0,841))),
            timing=ToiletTiming(90,0,true,15,15,0,0,at.toInstant().toEpochMilli(),"test",origin))
        assertTrue(search(DetourSession(),listOf(p)).candidates.isEmpty())
    }
}
