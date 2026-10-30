package com.eveningoutpost.dexdrip.garmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Watch → phone glucose v1 (Documentation/technical/GARMIN_WATCH_SUPPORT.md). */
public class GarminWatchMessagesTest {

    private static final long NOW_MS = 1_793_000_000_000L; // 2026-10-26, fits Connect IQ Number
    private static final int NOW_S = (int) (NOW_MS / 1000);

    @Test
    public void fieldCapabilitiesAdvertisePerFieldVersions() {
        final Map<String, Object> caps = GarminWatchMessages.capabilitiesMessage();
        assertEquals("caps", caps.get("t"));
        assertEquals(1, caps.get("v"));
        assertTrue(GarminWatchMessages.isCapabilitiesMessage(caps));
        final String fields = (String) caps.get("f");
        assertTrue(GarminWatchMessages.supportsField("|" + fields + "|", "collector.c", 2));
        assertFalse(fields.contains("slot."));
        assertTrue(fields.contains("glucose.rel=1"));
        assertFalse(fields.contains("pump_status.t"));
        assertFalse(GarminWatchMessages.supportsField(null, "slot.s", 2));
        assertTrue(GarminWatchMessages.supportsField(null, "glucose.mg", 1));
        assertFalse(GarminWatchMessages.supportsField("|slot.s=1|", "slot.s", 2));
        assertTrue(GarminWatchMessages.supportsField("|slot.s=3|", "slot.s", 2));
        assertFalse(GarminWatchMessages.supportsField(null, "key.name", 2));
    }

    @Test
    public void capabilitiesCanAskForAReplyOnce() {
        final Map<String, Object> ask = GarminWatchMessages.capabilitiesMessage(true);
        assertTrue(GarminWatchMessages.isCapabilitiesMessage(ask));
        assertTrue(GarminWatchMessages.wantsCapabilitiesReply(ask));
        final Map<String, Object> reply = GarminWatchMessages.capabilitiesMessage(false);
        assertFalse(GarminWatchMessages.wantsCapabilitiesReply(reply));
        assertFalse(GarminWatchMessages.wantsCapabilitiesReply(GarminWatchMessages.capabilitiesMessage()));
        assertTrue(((String) ask.get("f")).contains("caps.r=2"));
    }

    @Test
    public void malformedCapabilitiesAreRejected() {
        final Map<String, Object> caps = new HashMap<>();
        caps.put("t", "caps");
        caps.put("v", 1);
        caps.put("f", new Object());
        assertFalse(GarminWatchMessages.isCapabilitiesMessage(caps));
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
                GarminWatchMessages.collectorMessage("watch", NOW_S));
        assertNotNull(c);
        assertTrue(c.watch);
        assertEquals(NOW_S, c.changedAtSeconds);
        assertFalse(GarminWatchMessages.parseCollector(GarminWatchMessages.collectorMessage("phone", 0)).watch);
    }

    @Test
    public void malformedCollectorIsRejected() {
        final Map<String, Object> m = GarminWatchMessages.collectorMessage("watch", NOW_S);
        m.put("c", "neither");
        assertNull(GarminWatchMessages.parseCollector(m));
        final Map<String, Object> v2 = GarminWatchMessages.collectorMessage("watch", NOW_S);
        v2.put("v", 2);
        assertNull(GarminWatchMessages.parseCollector(v2));
        final Map<String, Object> noTime = GarminWatchMessages.collectorMessage("watch", NOW_S);
        noTime.remove("ts");
        assertNull(GarminWatchMessages.parseCollector(noTime));
    }

    @Test
    public void newerCollectorChoiceWinsAndPhoneKeepsTies() {
        final GarminWatchMessages.Collector c = GarminWatchMessages.parseCollector(
                GarminWatchMessages.collectorMessage("watch", NOW_S));
        assertTrue(GarminWatchMessages.replacesPhoneChoice(c, NOW_S - 1));
        assertFalse(GarminWatchMessages.replacesPhoneChoice(c, NOW_S));
        assertFalse(GarminWatchMessages.replacesPhoneChoice(c, NOW_S + 60));
    }

    @Test
    public void bothIsAWatchCollectorMode() {
        final GarminWatchMessages.Collector c = GarminWatchMessages.parseCollector(
                GarminWatchMessages.collectorMessage("both", NOW_S));
        assertNotNull(c);
        assertTrue(c.watch);
        assertEquals("both", c.mode);
    }

    @Test
    public void collectorModesAreTheFiveOwnerChoices() {
        for (String mode : new String[]{"none", "phone", "watch02", "watch03", "dual"}) {
            final GarminWatchMessages.Collector c = GarminWatchMessages.parseCollector(
                    GarminWatchMessages.collectorMessage(mode, NOW_S));
            assertNotNull(mode, c);
            assertEquals(mode, c.mode);
        }
        assertFalse(GarminWatchMessages.parseCollector(GarminWatchMessages.collectorMessage("none", NOW_S)).watch);
        assertTrue(GarminWatchMessages.parseCollector(GarminWatchMessages.collectorMessage("watch02", NOW_S)).watch);
        assertNull(GarminWatchMessages.parseCollector(GarminWatchMessages.collectorMessage("watch04", NOW_S)));
    }

    @Test
    public void olderWatchesGetAndSendTheOlderValues() {
        assertEquals("watch02", GarminWatchMessages.wireCollectorValue("watch02", true));
        assertEquals("watch", GarminWatchMessages.wireCollectorValue("watch02", false));
        assertEquals("watch", GarminWatchMessages.wireCollectorValue("watch03", false));
        assertEquals("both", GarminWatchMessages.wireCollectorValue("dual", false));
        assertEquals("phone", GarminWatchMessages.wireCollectorValue("none", false));
        assertEquals("phone", GarminWatchMessages.wireCollectorValue("phone", false));
        assertEquals("dual", GarminWatchMessages.fromLegacyCollector("both", "phone"));
        assertEquals("watch02", GarminWatchMessages.fromLegacyCollector("watch", "watch02"));
        assertEquals("watch03", GarminWatchMessages.fromLegacyCollector("watch", "phone"));
        assertEquals("phone", GarminWatchMessages.fromLegacyCollector("phone", "watch03"));
        assertEquals("none", GarminWatchMessages.fromLegacyCollector("none", "phone"));
    }
}
