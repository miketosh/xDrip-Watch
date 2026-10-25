package com.eveningoutpost.dexdrip.tandem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBasalStatusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.NonControlIQIOBResponse;

public class TandemX2StatusMapperTest {
    @Test
    public void mapsControlIqIobUsingPumpSelectedAlgorithm() {
        ControlIQIOBResponse response = new ControlIQIOBResponse(1250, 1800, 2500, 3450, 0);
        TandemX2PumpSnapshot.IobValue value = TandemX2StatusMapper.fromControlIqIob(response, 100L);
        assertNotNull(value);
        assertEquals("MUDALIAR", value.getAlgorithm());
        assertEquals(1.25, value.getUnits(), 0.00001);
        assertEquals(100L, value.getReceivedAtMillis());
    }

    @Test
    public void mapsSwanIobWhenPumpSelectsSwanAlgorithm() {
        ControlIQIOBResponse response = new ControlIQIOBResponse(1250, 1800, 2500, 3450, 1);
        TandemX2PumpSnapshot.IobValue value = TandemX2StatusMapper.fromControlIqIob(response, 200L);
        assertNotNull(value);
        assertEquals("SWAN_6HR", value.getAlgorithm());
        assertEquals(3.45, value.getUnits(), 0.00001);
    }

    @Test
    public void unknownControlIqAlgorithmDoesNotBecomeZero() {
        ControlIQIOBResponse response = new ControlIQIOBResponse(1250, 1800, 2500, 3450, 99);
        TandemX2PumpSnapshot.IobValue value = TandemX2StatusMapper.fromControlIqIob(response, 300L);
        assertNotNull(value);
        assertEquals("UNKNOWN", value.getAlgorithm());
        assertNull(value.getUnits());
    }

    @Test
    public void mapsNonControlIqIobAsInsulinUnits() {
        NonControlIQIOBResponse response = new NonControlIQIOBResponse(1550, 900, 2000);
        TandemX2PumpSnapshot.IobValue value = TandemX2StatusMapper.fromNonControlIqIob(response, 400L);
        assertNotNull(value);
        assertEquals("NON_CONTROL_IQ", value.getAlgorithm());
        assertEquals(1.55, value.getUnits(), 0.00001);
    }

    @Test
    public void preservesBasalFieldsAsRawUntilScaleIsVerified() {
        CurrentBasalStatusResponse response = new CurrentBasalStatusResponse(12500, 8000, 3);
        TandemX2PumpSnapshot.BasalValue value = TandemX2StatusMapper.fromBasal(response, 500L);
        assertNotNull(value);
        assertEquals(12500L, value.getProfileBasalRateRaw());
        assertEquals(8000L, value.getCurrentBasalRateRaw());
        assertEquals(3, value.getModifiedBitmask());
    }

    @Test
    public void noCurrentBolusSentinelIsNotDisplayedAsZeroUnits() {
        CurrentBolusStatusResponse response = new CurrentBolusStatusResponse(0, 0, 0L, 0L, 0, 0);
        TandemX2PumpSnapshot.CurrentBolusValue value = TandemX2StatusMapper.fromCurrentBolus(response, 600L);
        assertNotNull(value);
        assertEquals("NO_CURRENT_BOLUS", value.getStatus());
        assertNull(value.getRequestedUnits());
        assertNull(value.getPumpTimestampMillis());
    }

    @Test
    public void mapsCurrentBolusAmountAndTimestamp() {
        CurrentBolusStatusResponse response = new CurrentBolusStatusResponse(1, 7, 1000L, 2500L, 1, 0);
        TandemX2PumpSnapshot.CurrentBolusValue value = TandemX2StatusMapper.fromCurrentBolus(response, 700L);
        assertNotNull(value);
        assertEquals("DELIVERING", value.getStatus());
        assertEquals(2.5, value.getRequestedUnits(), 0.00001);
        assertEquals(Integer.valueOf(7), value.getBolusId());
        assertNotNull(value.getPumpTimestampMillis());
        assertEquals(700L, value.getReceivedAtMillis());
    }
}
