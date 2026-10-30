package com.eveningoutpost.dexdrip.garmin;

import androidx.annotation.Nullable;

import com.eveningoutpost.dexdrip.tandem.TandemX2PumpSnapshot;

import java.util.HashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * xDrip-Watch: the versioned phone/watch messages of
 * Documentation/technical/LOCAL_DATA_EXCHANGE_ARCHITECTURE.md, section 6. Pure Java so both
 * directions are unit-tested. Values are integers (milliunits, mg/dL, epoch seconds) because
 * Connect IQ's Number is 32-bit; an unknown value is left out, never sent as 0 or null.
 */
public final class GarminWatchMessages {
    private GarminWatchMessages() {}

    public static final String PUMP_STATUS = "pump_status";
    public static final String GLUCOSE = "glucose";
    public static final String COLLECTOR = "collector";
    public static final String SLOT = "slot";
    public static final int VERSION = 1;
    // Field-level capabilities: slot fields are version 2 because older bridge
    // peers may understand collector v1 but not the later slot message.
    private static final String LOCAL_FIELD_VERSIONS = "collector.c=1|collector.ts=1|slot.s=2|slot.ts=2|pump_status.sent=1|pump_status.seq=1|pump_status.link=1|pump_status.iob=1|pump_status.iob_t=1|pump_status.cb_st=1|pump_status.cb_t=1|pump_status.cb_u=1|pump_status.cb_pt=1|glucose.tx=1|glucose.sq=1|glucose.mg=1|glucose.st=1|glucose.rt=1|glucose.tr=1|glucose.pr=1|glucose.alg=1|glucose.do=1|glucose.rel=1|key.key=1|key.tx=1|key.name=1";

    /** Advertise maximum supported versions for individual fields (flat string for Connect IQ v1). */
    public static Map<String, Object> capabilitiesMessage() {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", "caps");
        m.put("v", VERSION);
        m.put("f", LOCAL_FIELD_VERSIONS);
        return m;
    }

    /** Exact field-version lookup; unknown peers default to established v1 fields only. */
    public static boolean supportsField(@Nullable String fields, String field, int requiredVersion) {
        if (fields == null || fields.isEmpty()) return requiredVersion <= 1;
        return fields.contains("|" + field + "=" + requiredVersion + "|");
    }

    public static boolean isCapabilitiesMessage(@Nullable Map<?, ?> m) {
        return m != null && "caps".equals(m.get("t"))
                && Integer.valueOf(VERSION).equals(asInt(m.get("v")))
                && m.get("f") instanceof String
                && ((String) m.get("f")).length() <= 512;
    }

    /** Which side connects to the G7 ("watch", "phone" or "both"), and when that was chosen (epoch seconds). */
    public static final class Collector {
        public final String mode;
        public final boolean watch;
        public final long changedAtSeconds;

        Collector(String mode, long changedAtSeconds) {
            this.mode = mode;
            this.watch = !"phone".equals(mode);
            this.changedAtSeconds = changedAtSeconds;
        }
    }

    /** Both directions: {@code {t: "collector", v: 1, c: "watch"|"phone"|"both", ts}}; null if malformed. */
    @Nullable
    public static Collector parseCollector(@Nullable Map<?, ?> m) {
        if (m == null || !COLLECTOR.equals(m.get("t"))) return null;
        if (!Integer.valueOf(VERSION).equals(asInt(m.get("v")))) return null;
        final Object c = m.get("c");
        final Integer ts = asInt(m.get("ts"));
        if (ts == null || ts < 0 || !("watch".equals(c) || "phone".equals(c) || "both".equals(c))) return null;
        return new Collector((String) c, ts);
    }

    public static Map<String, Object> collectorMessage(String mode, long changedAtSeconds) {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", COLLECTOR);
        m.put("v", VERSION);
        m.put("c", mode);
        m.put("ts", (int) changedAtSeconds);
        return m;
    }

    /** Both directions: shared slot choice and change timestamp, {@code {t: "slot", v: 1, s, ts}}. */
    public static Map<String, Object> slotMessage(String choice) {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", SLOT);
        m.put("v", VERSION);
        m.put("s", "02".equals(choice) || "03".equals(choice) ? choice : "auto");
        return m;
    }

    /** Versioned G7 slot preference, exchanged in both directions. */
    public static final class Slot {
        public final String choice;
        public final long changedAtSeconds;

        Slot(String choice, long changedAtSeconds) {
            this.choice = choice;
            this.changedAtSeconds = changedAtSeconds;
        }
    }

    /** Both directions: {t: "slot", v: 1, s: "auto"|"02"|"03", ts}. */
    @Nullable
    public static Slot parseSlot(@Nullable Map<?, ?> m) {
        if (m == null || !SLOT.equals(m.get("t"))) return null;
        if (!Integer.valueOf(VERSION).equals(asInt(m.get("v")))) return null;
        final Object s = m.get("s");
        final Integer ts = asInt(m.get("ts"));
        if (ts == null || ts < 0 || !("auto".equals(s) || "02".equals(s) || "03".equals(s))) return null;
        return new Slot((String) s, ts);
    }

    public static Map<String, Object> slotMessage(String choice) {
        return slotMessage(choice, 0);
    }

    public static Map<String, Object> slotMessage(String choice, long changedAtSeconds) {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", SLOT);
        m.put("v", VERSION);
        m.put("s", "02".equals(choice) || "03".equals(choice) ? choice : "auto");
        m.put("ts", (int) Math.max(0, changedAtSeconds));
        return m;
    }

    /** The newer choice wins; on a tie the phone keeps its own (the watch then takes the phone's). */
    public static boolean replacesPhoneChoice(Collector fromWatch, long phoneChangedAtSeconds) {
        return fromWatch.changedAtSeconds > phoneChangedAtSeconds;
    }

    /** Phone keeps its slot choice on a timestamp tie, just as it does for collector v1. */
    public static boolean replacesPhoneSlot(Slot fromWatch, long phoneChangedAtSeconds) {
        return fromWatch.changedAtSeconds > phoneChangedAtSeconds;
    }

    public static final String LINK_UP = "up";
    public static final String LINK_DOWN = "down";
    public static final String LINK_OFF = "off";

    /**
     * Phone → watch pump snapshot. Every pump field is pump-reported; basal and last-bolus keys
     * are reserved in the contract and left out until their source is verified.
     */
    public static Map<String, Object> pumpStatus(@Nullable TandemX2PumpSnapshot s, String link,
                                                 int seq, long nowMillis) {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", PUMP_STATUS);
        m.put("v", VERSION);
        m.put("sent", seconds(nowMillis));
        m.put("seq", seq);
        m.put("link", LINK_UP.equals(link) || LINK_DOWN.equals(link) ? link : LINK_OFF);
        if (s == null) return m;

        final TandemX2PumpSnapshot.IobValue iob = s.getControlIqIob() != null
                && s.getControlIqIob().getUnits() != null ? s.getControlIqIob() : s.getNonControlIqIob();
        if (iob != null && iob.getUnits() != null && iob.getUnits() >= 0) {
            m.put("iob", (int) Math.round(iob.getUnits() * 1000));
            m.put("iob_t", seconds(iob.getReceivedAtMillis()));
        }

        final TandemX2PumpSnapshot.CurrentBolusValue cb = s.getCurrentBolus();
        final String state = cb == null ? null : bolusState(cb.getStatus());
        if (state != null) {
            m.put("cb_st", state);
            m.put("cb_t", seconds(cb.getReceivedAtMillis()));
            if (!"none".equals(state)) {
                if (cb.getRequestedUnits() != null && cb.getRequestedUnits() >= 0) {
                    m.put("cb_u", (int) Math.round(cb.getRequestedUnits() * 1000));
                }
                if (cb.getPumpTimestampMillis() != null) m.put("cb_pt", seconds(cb.getPumpTimestampMillis()));
            }
        }
        return m;
    }

    @Nullable
    static String bolusState(@Nullable String pumpX2Status) {
        if (pumpX2Status == null) return null;
        switch (pumpX2Status) {
            case "NO_CURRENT_BOLUS": return "none";
            case "REQUESTING": return "requesting";
            case "DELIVERING": return "delivering";
            case "ALREADY_DELIVERED_OR_INVALID": return "done";
            default: return null;
        }
    }

    /** A watch → phone reading after validation; see {@link #parseGlucose}. */
    public static final class Glucose {
        public final String sensor;
        public final int sequence;
        public final int mgdl;
        public final long readingTimeSeconds;
        public final long receivedSeconds;
        @Nullable public final Integer trendTenthsPerMinute;

        Glucose(String sensor, int sequence, int mgdl, long readingTimeSeconds, long receivedSeconds,
                @Nullable Integer trendTenthsPerMinute) {
            this.sensor = sensor;
            this.sequence = sequence;
            this.mgdl = mgdl;
            this.readingTimeSeconds = readingTimeSeconds;
            this.receivedSeconds = receivedSeconds;
            this.trendTenthsPerMinute = trendTenthsPerMinute;
        }
    }

    /** Outcome of parsing a watch message: a reading, or the reason it was not accepted. */
    public static final class Parsed {
        @Nullable public final Glucose glucose;
        public final String reason;

        private Parsed(@Nullable Glucose glucose, String reason) {
            this.glucose = glucose;
            this.reason = reason;
        }

        static Parsed reject(String reason) {
            return new Parsed(null, reason);
        }
    }

    static final int MIN_MGDL = 40;
    static final int MAX_MGDL = 400;
    static final long MAX_FUTURE_SECONDS = 300;
    static final long MAX_AGE_SECONDS = 24 * 3600;

    /**
     * Watch → phone glucose. Accepts only readings the watch marked reliable (sensor algorithm
     * state OK, not display-only, ≤ 15 min old when received), in range, and dated sensibly.
     */
    public static Parsed parseGlucose(@Nullable Map<?, ?> m, long nowMillis) {
        if (m == null || !GLUCOSE.equals(m.get("t"))) return Parsed.reject("not a glucose message");
        if (!Integer.valueOf(VERSION).equals(asInt(m.get("v")))) return Parsed.reject("unsupported version");
        final Object tx = m.get("tx");
        final Integer sq = asInt(m.get("sq"));
        final Integer mg = asInt(m.get("mg"));
        final Integer st = asInt(m.get("st"));
        final Integer rt = asInt(m.get("rt"));
        if (!(tx instanceof String) || ((String) tx).isEmpty() || ((String) tx).length() > 8
                || sq == null || st == null || rt == null) {
            return Parsed.reject("missing or malformed identity/time fields");
        }
        if (!Boolean.TRUE.equals(m.get("rel"))) return Parsed.reject("not marked reliable by the watch");
        if (mg == null || mg < MIN_MGDL || mg > MAX_MGDL) return Parsed.reject("glucose out of range");
        final long now = nowMillis / 1000;
        if (st > now + MAX_FUTURE_SECONDS || rt > now + MAX_FUTURE_SECONDS) return Parsed.reject("time in the future");
        if (st < now - MAX_AGE_SECONDS || st > rt + MAX_FUTURE_SECONDS) return Parsed.reject("reading time implausible");
        return new Parsed(new Glucose((String) tx, sq, mg, st, rt, asInt(m.get("tr"))), "accepted");
    }

    /** Drops repeats of (sensor, sequence); bounded, oldest entries forgotten first. */
    public static final class Deduper {
        private final int capacity;
        private final LinkedHashSet<String> seen = new LinkedHashSet<>();

        public Deduper(int capacity) {
            this.capacity = capacity;
        }

        public synchronized boolean isNew(Glucose g) {
            final String key = g.sensor + "#" + g.sequence;
            if (seen.contains(key)) return false;
            seen.add(key);
            if (seen.size() > capacity) {
                final Set<String> view = seen;
                view.remove(view.iterator().next());
            }
            return true;
        }
    }

    private static int seconds(long millis) {
        return (int) (millis / 1000);
    }

    /** Connect IQ delivers whole numbers as Integer or Long; anything else is malformed. */
    @Nullable
    private static Integer asInt(@Nullable Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Long && (Long) o <= Integer.MAX_VALUE && (Long) o >= Integer.MIN_VALUE) {
            return ((Long) o).intValue();
        }
        return null;
    }
}
