package com.eveningoutpost.dexdrip.garmin;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    public static final int VERSION = 1;

    /**
     * Connect IQ app IDs of the watch apps that speak these messages: G7 Scout (development,
     * github.com/miketosh/garmin7-scout) and Garmin7-Connector (public testing,
     * github.com/miketosh/Garmin7-Connector). xDrip listens to both and sends to those it has heard
     * from, or to G7 Scout's before any watch app has spoken (2026-10-30).
     */
    public static final String SCOUT_APP_ID = "8d8a5d1f6b4a4f5fa8d3a7f0d2c9b641";
    public static final String CONNECTOR_APP_ID = "c47a6e2d93b54f2aa1f608c4d9e73b51";
    public static final String[] WATCH_APP_IDS = {SCOUT_APP_ID, CONNECTOR_APP_ID};

    /** Adds a watch app to the comma-separated list of apps heard from; unknown IDs are ignored. */
    public static String addHeardApp(@Nullable String heard, @Nullable String appId) {
        final List<String> apps = heardApps(heard);
        if (appId != null) {
            for (final String known : WATCH_APP_IDS) {
                if (known.equalsIgnoreCase(appId) && !apps.contains(known)) apps.add(known);
            }
        }
        final StringBuilder out = new StringBuilder();
        for (final String app : apps) {
            if (out.length() > 0) out.append(",");
            out.append(app);
        }
        return out.toString();
    }

    /** The watch apps to send to: those heard from, else G7 Scout's. */
    public static List<String> sendTargets(@Nullable String heard) {
        final List<String> apps = heardApps(heard);
        if (apps.isEmpty()) apps.add(SCOUT_APP_ID);
        return apps;
    }

    private static List<String> heardApps(@Nullable String heard) {
        final List<String> apps = new ArrayList<>();
        if (heard == null) return apps;
        for (final String part : heard.split(",")) {
            for (final String known : WATCH_APP_IDS) {
                if (known.equalsIgnoreCase(part.trim()) && !apps.contains(known)) apps.add(known);
            }
        }
        return apps;
    }
    // Field-level capabilities: slot fields are version 2 because older bridge
    // peers may understand collector v1 but not the later slot message.
    private static final String LOCAL_FIELD_VERSIONS = "caps.t=1|caps.v=1|caps.f=1|caps.r=2|collector.t=1|collector.v=1|collector.c=2|collector.ts=1|glucose.t=1|glucose.v=1|glucose.tx=1|glucose.sq=1|glucose.mg=1|glucose.st=1|glucose.rt=1|glucose.tr=1|glucose.rel=1";

    /** Advertise maximum supported versions for individual fields (flat string for Connect IQ v1). */
    public static Map<String, Object> capabilitiesMessage() {
        return capabilitiesMessage(false);
    }

    /**
     * With {@code requestReply} the message carries {@code r: 1}: the peer answers with its own
     * capabilities once (a reply carries no {@code r}, so it never loops). Each side used to learn
     * the other's only when it started second (review 2026-10-30).
     */
    public static Map<String, Object> capabilitiesMessage(boolean requestReply) {
        final Map<String, Object> m = new HashMap<>();
        m.put("t", "caps");
        m.put("v", VERSION);
        m.put("f", LOCAL_FIELD_VERSIONS);
        if (requestReply) m.put("r", 1);
        return m;
    }

    /** Exact field-version lookup; unknown peers default to established v1 fields only. */
    public static boolean supportsField(@Nullable String fields, String field, int requiredVersion) {
        if (fields == null || fields.isEmpty()) return requiredVersion <= 1;
        final String prefix = "|" + field + "=";
        final int start = fields.indexOf(prefix);
        if (start < 0) return false;
        final int valueStart = start + prefix.length();
        final int end = fields.indexOf('|', valueStart);
        if (end < 0 || end == valueStart) return false;
        try {
            return Integer.parseInt(fields.substring(valueStart, end)) >= requiredVersion;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A capabilities message that asks for ours in return. */
    public static boolean wantsCapabilitiesReply(@Nullable Map<?, ?> m) {
        return m != null && Integer.valueOf(1).equals(asInt(m.get("r")));
    }

    public static boolean isCapabilitiesMessage(@Nullable Map<?, ?> m) {
        return m != null && "caps".equals(m.get("t"))
                && Integer.valueOf(VERSION).equals(asInt(m.get("v")))
                && m.get("f") instanceof String
                && ((String) m.get("f")).length() <= 512;
    }

    /**
     * Collector mode values (owner, 2026-10-30), field collector.c version 2: "none", "phone",
     * "watch02", "watch03", "dual". A peer without version 2 sends and expects the older
     * "watch", "phone" and "both" (see wireCollectorValue / fromLegacyCollector).
     */
    public static boolean isCollectorMode(@Nullable Object value) {
        return "none".equals(value) || "phone".equals(value) || "watch02".equals(value)
                || "watch03".equals(value) || "dual".equals(value);
    }

    /** The value to send: the mode itself, or the older value for a peer without collector.c 2. */
    public static String wireCollectorValue(String mode, boolean peerKnowsModes) {
        if (peerKnowsModes) return mode;
        if ("dual".equals(mode)) return "both";
        return "watch02".equals(mode) || "watch03".equals(mode) ? "watch" : "phone";
    }

    /** A received value as a mode; an older "watch" keeps the current watch slot, else 03. */
    public static String fromLegacyCollector(String value, String currentMode) {
        if (isCollectorMode(value)) return value;
        if ("both".equals(value)) return "dual";
        if ("watch".equals(value)) {
            return "watch02".equals(currentMode) || "watch03".equals(currentMode) ? currentMode : "watch03";
        }
        return "phone";
    }

    /** Who connects to the G7 (a mode or an older value), and when that was chosen (epoch seconds). */
    public static final class Collector {
        public final String mode;
        public final boolean watch;
        public final long changedAtSeconds;

        Collector(String mode, long changedAtSeconds) {
            this.mode = mode;
            this.watch = !"phone".equals(mode) && !"none".equals(mode);
            this.changedAtSeconds = changedAtSeconds;
        }
    }

    /** Both directions: {@code {t: "collector", v: 1, c: <mode or older value>, ts}}; null if malformed. */
    @Nullable
    public static Collector parseCollector(@Nullable Map<?, ?> m) {
        if (m == null || !COLLECTOR.equals(m.get("t"))) return null;
        if (!Integer.valueOf(VERSION).equals(asInt(m.get("v")))) return null;
        final Object c = m.get("c");
        final Integer ts = asInt(m.get("ts"));
        if (ts == null || ts < 0 || !(isCollectorMode(c) || "watch".equals(c) || "both".equals(c))) return null;
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
