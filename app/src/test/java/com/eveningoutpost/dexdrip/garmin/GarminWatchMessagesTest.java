package com.eveningoutpost.dexdrip.garmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eveningoutpost.dexdrip.tandem.TandemX2PumpSnapshot;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Both directions of the v1 phone/watch contract (LOCAL_DATA_EXCHANGE_ARCHITECTURE.md §6). */
public class GarminWatchMessagesTest {

    private static final long NOW_MS = 1_793_000_000_000L; // 2026-10-26, fits Connect IQ Number
    private static final int NOW_S = (int) (NOW_MS / 1000);

    // --- pump_status (phone → watch) ---

    @Test
    public void emptySnapshotSendsHeaderOnly() {
        final Map<String, Object> m = GarminWatchMessages.pumpStatus(null, "up", 7, NOW_MS);
        assertEquals("pump_status", m.get("t"));
        assertEquals(1, m.get("v"));
        assertEquals(NOW_S, m.get("sent"));
        assertEquals(7, m.get("seq"));
        assertEquals("up", m.get("link"));
        assertEquals(5, m.size());
    }

    @Test
    public void unknownLinkBecomesOff() {
        assertEquals("off", GarminWatchMessages.pumpStatus(null, "connected", 1, NOW_MS).get("link"));
    }

    @Test
    public void iobIsMilliunitsWithItsOwnTimeAndZeroStaysZero() {
        final TandemX2PumpSnapshot s = new TandemX2PumpSnapshot(NOW_MS,
                new TandemX2PumpSnapshot.IobValue(1.25, "MUDALIAR", NOW_MS - 60_000), null, null, null);
        final Map<String, Object> m = GarminWatchMessages.pumpStatus(s, "up", 1, NOW_MS);
        assertEquals(1250, m.get("iob"));
        assertEquals(NOW_S - 60, m.get("iob_t"));

        final TandemX2PumpSnapshot zero = new TandemX2PumpSnapshot(NOW_MS,
                new TandemX2PumpSnapshot.IobValue(0.0, "MUDALIAR", NOW_MS), null, null, null);
        assertEquals(0, GarminWatchMessages.pumpStatus(zero, "up", 1, NOW_MS).get("iob"));
    }

    @Test
    public void unknownIobIsLeftOutNotZero() {
        final TandemX2PumpSnapshot s = new TandemX2PumpSnapshot(NOW_MS,
                new TandemX2PumpSnapshot.IobValue(null, "UNKNOWN", NOW_MS), null, null, null);
        final Map<String, Object> m = GarminWatchMessages.pumpStatus(s, "up", 1, NOW_MS);
        assertFalse(m.containsKey("iob"));
        assertFalse(m.containsKey("iob_t"));
    }

    @Test
    public void controlIqIobPreferredNonControlIqAsFallback() {
        final TandemX2PumpSnapshot s = new TandemX2PumpSnapshot(NOW_MS,
                new TandemX2PumpSnapshot.IobValue(null, "UNKNOWN", NOW_MS),
                new TandemX2PumpSnapshot.IobValue(0.8, "NON_CONTROL_IQ", NOW_MS), null, null);
        assertEquals(800, GarminWatchMessages.pumpStatus(s, "up", 1, NOW_MS).get("iob"));
    }

    @Test
    public void currentBolusStatesAndNoBolusIsAKnownFact() {
        final TandemX2PumpSnapshot delivering = new TandemX2PumpSnapshot(NOW_MS, null, null, null,
                new TandemX2PumpSnapshot.CurrentBolusValue("DELIVERING", 2.5, 7, NOW_MS - 30_000, NOW_MS));
        final Map<String, Object> m = GarminWatchMessages.pumpStatus(delivering, "up", 1, NOW_MS);
        assertEquals("delivering", m.get("cb_st"));
        assertEquals(2500, m.get("cb_u"));
        assertEquals(NOW_S - 30, m.get("cb_pt"));
        assertEquals(NOW_S, m.get("cb_t"));

        final TandemX2PumpSnapshot none = new TandemX2PumpSnapshot(NOW_MS, null, null, null,
                new TandemX2PumpSnapshot.CurrentBolusValue("NO_CURRENT_BOLUS", null, null, null, NOW_MS));
        final Map<String, Object> n = GarminWatchMessages.pumpStatus(none, "up", 1, NOW_MS);
        assertEquals("none", n.get("cb_st"));
        assertFalse(n.containsKey("cb_u"));

        final TandemX2PumpSnapshot unknown = new TandemX2PumpSnapshot(NOW_MS, null, null, null,
                new TandemX2PumpSnapshot.CurrentBolusValue("SOMETHING_NEW", 1.0, 1, null, NOW_MS));
        assertFalse(GarminWatchMessages.pumpStatus(unknown, "up", 1, NOW_MS).containsKey("cb_st"));
    }

    @Test
    public void unverifiedBasalIsNeverSent() {
        final TandemX2PumpSnapshot s = new TandemX2PumpSnapshot(NOW_MS, null, null,
                new TandemX2PumpSnapshot.BasalValue(800, 800, 0, NOW_MS), null);
        final Map<String, Object> m = GarminWatchMessages.pumpStatus(s, "up", 1, NOW_MS);
        assertFalse(m.containsKey("br"));
        assertFalse(m.containsKey("br_t"));
    }

    @Test
    public void everyValueIsAnIntegerOrString() {
        final TandemX2PumpSnapshot s = new TandemX2PumpSnapshot(NOW_MS,
                new TandemX2PumpSnapshot.IobValue(3.333, "SWAN_6HR", NOW_MS), null, null,
                new TandemX2PumpSnapshot.CurrentBolusValue("DELIVERING", 1.0, 2, NOW_MS, NOW_MS));
        for (Object v : GarminWatchMessages.pumpStatus(s, "up", 1, NOW_MS).values()) {
            assertTrue(String.valueOf(v), v instanceof Integer || v instanceof String);
        }
    }

    // --- glucose (watch → phone) ---

    private static Map<String, Object> reading() {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", "glucose");
        m.put("v", 1);
        m.put("tx", "DXCMz8");
        m.put("sq", 1234);
        m.put("mg", 112);
        m.put("st", NOW_S - 120);
        m.put("rt", NOW_S - 60);
        m.put("tr", -12);
        m.put("rel", true);
        return m;
    }

    @Test
    public void reliableReadingIsAccepted() {
        final GarminWatchMessages.Parsed p = GarminWatchMessages.parseGlucose(reading(), NOW_MS);
        assertNotNull(p.reason, p.glucose);
        assertEquals("DXCMz8", p.glucose.sensor);
        assertEquals(1234, p.glucose.sequence);
        assertEquals(112, p.glucose.mgdl);
        assertEquals(NOW_S - 120, p.glucose.readingTimeSeconds);
        assertEquals(Integer.valueOf(-12), p.glucose.trendTenthsPerMinute);
    }

    @Test
    public void longValuesFromConnectIqAreAccepted() {
        final Map<String, Object> m = reading();
        m.put("st", (long) (NOW_S - 120));
        m.put("mg", 112L);
        assertNotNull(GarminWatchMessages.parseGlucose(m, NOW_MS).glucose);
    }

    @Test
    public void trendIsOptional() {
        final Map<String, Object> m = reading();
        m.remove("tr");
        final GarminWatchMessages.Parsed p = GarminWatchMessages.parseGlucose(m, NOW_MS);
        assertNotNull(p.glucose);
        assertNull(p.glucose.trendTenthsPerMinute);
    }

    @Test
    public void unreliableOrDisplayOnlyReadingsAreRejected() {
        final Map<String, Object> m = reading();
        m.put("rel", false);
        assertNull(GarminWatchMessages.parseGlucose(m, NOW_MS).glucose);
        m.remove("rel");
        assertNull(GarminWatchMessages.parseGlucose(m, NOW_MS).glucose);
    }

    @Test
    public void malformedMessagesAreRejected() {
        for (String key : new String[]{"tx", "sq", "st", "rt", "mg"}) {
            final Map<String, Object> m = reading();
            m.remove(key);
            assertNull(key, GarminWatchMessages.parseGlucose(m, NOW_MS).glucose);
        }
        final Map<String, Object> wrongType = reading();
        wrongType.put("mg", 112.0);
        assertNull(GarminWatchMessages.parseGlucose(wrongType, NOW_MS).glucose);
        final Map<String, Object> v2 = reading();
        v2.put("v", 2);
        assertNull(GarminWatchMessages.parseGlucose(v2, NOW_MS).glucose);
        final Map<String, Object> other = reading();
        other.put("t", "pump_status");
        assertNull(GarminWatchMessages.parseGlucose(other, NOW_MS).glucose);
        assertNull(GarminWatchMessages.parseGlucose(null, NOW_MS).glucose);
    }

    @Test
    public void outOfRangeAndBadTimesAreRejected() {
        final Map<String, Object> low = reading();
        low.put("mg", 39);
        assertNull(GarminWatchMessages.parseGlucose(low, NOW_MS).glucose);
        final Map<String, Object> high = reading();
        high.put("mg", 401);
        assertNull(GarminWatchMessages.parseGlucose(high, NOW_MS).glucose);
        final Map<String, Object> future = reading();
        future.put("st", NOW_S + 600);
        future.put("rt", NOW_S + 600);
        assertNull(GarminWatchMessages.parseGlucose(future, NOW_MS).glucose);
        final Map<String, Object> old = reading();
        old.put("st", NOW_S - 2 * 24 * 3600);
        assertNull(GarminWatchMessages.parseGlucose(old, NOW_MS).glucose);
    }

    @Test
    public void deduperDropsRepeatsAndStaysBounded() {
        final GarminWatchMessages.Deduper d = new GarminWatchMessages.Deduper(2);
        final GarminWatchMessages.Glucose a = GarminWatchMessages.parseGlucose(reading(), NOW_MS).glucose;
        assertTrue(d.isNew(a));
        assertFalse(d.isNew(a));
        final Map<String, Object> m2 = reading();
        m2.put("sq", 1235);
        final Map<String, Object> m3 = reading();
        m3.put("sq", 1236);
        assertTrue(d.isNew(GarminWatchMessages.parseGlucose(m2, NOW_MS).glucose));
        assertTrue(d.isNew(GarminWatchMessages.parseGlucose(m3, NOW_MS).glucose));
        assertTrue(d.isNew(a)); // forgotten: capacity 2
    }

    // --- collector (both directions) ---

    @Test
    public void collectorRoundTrips() {
        final GarminWatchMessages.Collector c = GarminWatchMessages.parseCollector(
                GarminWatchMessages.collectorMessage(true, NOW_S));
        assertNotNull(c);
        assertTrue(c.watch);
        assertEquals(NOW_S, c.changedAtSeconds);
        assertFalse(GarminWatchMessages.parseCollector(GarminWatchMessages.collectorMessage(false, 0)).watch);
    }

    @Test
    public void malformedCollectorIsRejected() {
        final Map<String, Object> m = GarminWatchMessages.collectorMessage(true, NOW_S);
        m.put("c", "both");
        assertNull(GarminWatchMessages.parseCollector(m));
        final Map<String, Object> v2 = GarminWatchMessages.collectorMessage(true, NOW_S);
        v2.put("v", 2);
        assertNull(GarminWatchMessages.parseCollector(v2));
        final Map<String, Object> noTime = GarminWatchMessages.collectorMessage(true, NOW_S);
        noTime.remove("ts");
        assertNull(GarminWatchMessages.parseCollector(noTime));
    }

    @Test
    public void newerCollectorChoiceWinsAndPhoneKeepsTies() {
        final GarminWatchMessages.Collector c = GarminWatchMessages.parseCollector(
                GarminWatchMessages.collectorMessage(true, NOW_S));
        assertTrue(GarminWatchMessages.replacesPhoneChoice(c, NOW_S - 1));
        assertFalse(GarminWatchMessages.replacesPhoneChoice(c, NOW_S));
        assertFalse(GarminWatchMessages.replacesPhoneChoice(c, NOW_S + 60));
    }
}
