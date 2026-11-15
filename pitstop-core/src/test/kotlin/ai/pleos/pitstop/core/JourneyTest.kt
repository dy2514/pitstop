package ai.pleos.pitstop.core

import org.junit.Assert.*
import org.junit.Test

class JourneyTest {
    private val now=100000L
    private fun timing(included:Boolean=false) = ToiletTiming(60,30,included,15,30,0,15,now,"test")
    private val before=Comfort(Vent.FACE,0,0,8,"WHITE")
    private fun signal()=ArrivalSignal(10.0,5.0,0,0.0,0,true,true,true)
    private val consent=ArrivalConsent(true,true,true)

    @Test fun tttRanksFartherQuickAccessFirst() {
        val at=java.time.ZonedDateTime.parse("2026-11-15T14:00:00+09:00")
        val origin=GeoPoint(37.0,127.0)
        val hours=OpeningHours(true,listOf(DailyWindow((1..7).toSet(),0,1440)))
        fun pair(id:String,lat:Double,seconds:Int)=FacilityPair(id,id,id,"test",GeoPoint(lat,127.0),true,true,true,hours,hours,30,"test","free","test",at.toLocalDate(),facilityKind=FacilityKind.PUBLIC_TOILET,accessRestriction=AccessRestriction.NONE,timing=ToiletTiming(seconds,0,true,0,0,0,0,at.toInstant().toEpochMilli(),"test",origin))
        val result=PitStopEngine.search(origin,at.toInstant().toEpochMilli(),listOf(pair("mall",37.001,660),pair("station",37.007,120)),at,SearchOptions(Priority.FASTEST_TOILET))
        assertEquals("station",result.candidates.first().pair.id)
        assertEquals(120,result.candidates.first().tttSeconds)
    }
    @Test fun changedOriginInvalidatesRoute() { val p=GeoPoint(37.0,127.0);assertNull(timing().total(now,p));assertEquals(150,timing().copy(origin=p).total(now,p));assertNull(timing().copy(origin=p).total(now,GeoPoint(37.1,127.0))) }
    @Test fun addsAllSegments() { assertEquals(150,timing().total(now)) }
    @Test fun doesNotDoubleCountSignals() { assertEquals(120,timing(true).total(now)) }
    @Test fun includedUnknownSignalIsAllowed() { assertEquals(120,timing(true).copy(signal=null).total(now)) }
    @Test fun unknownSegmentNotZero() { assertNull(timing().copy(floors=null).total(now)) }
    @Test fun staleAndFutureRoutesRejected() { assertNull(timing().total(now+300001));assertNull(timing().total(now-1)) }
    @Test(expected=IllegalArgumentException::class) fun negativeTimeRejected() { timing().copy(parking=-1) }
    @Test fun heatRequiresConsent() { assertEquals(0,CareLease(before,CareOptions(),now).target.seatHeat) }
    @Test fun carePreservesDefrost() { assertEquals(Vent.DEFROST,CareLease(before.copy(vent=Vent.DEFROST),CareOptions(redirect=true),now).target.vent) }
    @Test fun confirmedCareRestores() { val c=CareLease(before,CareOptions(true,true,true),now);c.confirm(c.target);assertEquals(before,c.restore(c.target)) }
    @Test fun unconfirmedDoesNotRestore() { val c=CareLease(before,CareOptions(true),now);assertEquals(c.target,c.restore(c.target)) }
    @Test fun manualChangePreservedEvenIfChangedBack() { val c=CareLease(before,CareOptions(true),now);c.confirm(c.target);c.observe(c.target.copy(seatHeat=1));assertEquals(3,c.restore(c.target).seatHeat) }
    @Test fun timeoutBoundary() { val c=CareLease(before,CareOptions(),now);assertFalse(c.expired(now+179999));assertTrue(c.expired(now+180000)) }
    @Test(expected=IllegalArgumentException::class) fun unboundedHeatRejected() { CareOptions(durationMs=300001) }
    @Test fun arrivalRequestsOnce() { val g=ArrivalGate();assertEquals(4,g.evaluate(signal(),consent).size);assertTrue(g.evaluate(signal(),consent).isEmpty()) }
    @Test fun movingAndNonParkNeverUnlock() { assertTrue(ArrivalGate().evaluate(signal().copy(speedKmh=0.1),consent).isEmpty());assertTrue(ArrivalGate().evaluate(signal().copy(gearP=false),consent).isEmpty()) }
    @Test fun uncertainPositionRejected() { assertTrue(ArrivalGate().evaluate(signal().copy(distanceM=29.0),consent).isEmpty());assertTrue(ArrivalGate().evaluate(signal().copy(accuracyM=Double.NaN),consent).isEmpty()) }
    @Test fun staleTelemetryRejected() { assertTrue(ArrivalGate().evaluate(signal().copy(fixAgeMs=5001),consent).isEmpty());assertTrue(ArrivalGate().evaluate(signal().copy(speedAgeMs=1001),consent).isEmpty()) }
    @Test fun noConsentNoActuatorRequest() { assertEquals(setOf(ArrivalAction.ENTRANCE_BRIEFING),ArrivalGate().evaluate(signal(),ArrivalConsent())) }
    @Test fun hazardOnlyForPermittedStop() { assertFalse(ArrivalAction.HAZARDS_ON in ArrivalGate().evaluate(signal().copy(permittedRoadsideStop=false),consent)) }
    @Test fun delayedBeltReleaseBriefsOnlyOnce() { val g=ArrivalGate();assertEquals(3,g.evaluate(signal().copy(beltReleased=false),consent).size);assertEquals(setOf(ArrivalAction.ENTRANCE_BRIEFING),g.evaluate(signal(),consent)) }
    @Test fun coachingCooldown() { val c=SmoothCoach();assertNotNull(c.acceleration(3.0,now,0));assertNull(c.acceleration(-3.0,now+14999,0));assertNotNull(c.acceleration(-3.0,now+15000,0)) }
    @Test fun staleOrInvalidAccelerationIgnored() { val c=SmoothCoach();assertNull(c.acceleration(Double.NaN,now,0));assertNull(c.acceleration(3.0,now,1001));assertNull(c.acceleration(1.0,now,0)) }
    @Test fun hazardOnceAndOnlyWithinLeadDistance() { val c=SmoothCoach();val h=RoadHazard("1",HazardKind.BUMP,40.0,now);assertNotNull(c.hazard(h,now,30.0));assertNull(c.hazard(h,now,30.0));assertNull(c.hazard(h.copy(id="2",distanceM=200.0),now,30.0));assertNull(c.hazard(h.copy(id="3"),now+5001,30.0)) }
}
