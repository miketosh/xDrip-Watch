package com.eveningoutpost.dexdrip.garmin;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Garmin watch support: the versioned messages from the G7 Scout watch app
 * (Documentation/technical/GARMIN_WATCH_SUPPORT.md). Pure Java so it is unit-tested. Values are
 * integers (mg/dL, epoch seconds) because Connect IQ's Number is 32-bit; an unknown value is left
 * out, never sent as 0 or null.
 */
public final class GarminWatchMessages {
    private GarminWatchMessages() {}

    public static final String GLUCOSE = "glucose";
    public static final String COLLECTOR = "collector";
    public static final String SLOT = "slot";
    public static final int VERSION = 1;

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

    /** Phone → watch: the watch's G7 slot, {@code {t: "slot", v: 1, s: "auto"|"02"|"03"}}. */
    public static Map<String, Object> slotMessage(String choice) {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", SLOT);
        m.put("v", VERSION);
        m.put("s", "02".equals(choice) || "03".equals(choice) ? choice : "auto");
        return m;
    }

    /** The newer choice wins; on a tie the phone keeps its own (the watch then takes the phone's). */
    public static boolean replacesPhoneChoice(Collector fromWatch, long phoneChangedAtSeconds) {
        return fromWatch.changedAtSeconds > phoneChangedAtSeconds;
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
