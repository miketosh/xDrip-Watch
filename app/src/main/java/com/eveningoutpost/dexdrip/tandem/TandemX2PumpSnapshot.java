package com.eveningoutpost.dexdrip.tandem;

import androidx.annotation.Nullable;

/**
 * Immutable snapshot of selected Tandem t:slim X2 responses. Null means unavailable/unknown,
 * never zero. Basal-rate fields remain in PumpX2's raw integer units until their scale is verified.
 */
public final class TandemX2PumpSnapshot {
    private final long snapshotReceivedAtMillis;
    @Nullable private final IobValue controlIqIob;
    @Nullable private final IobValue nonControlIqIob;
    @Nullable private final BasalValue basal;
    @Nullable private final CurrentBolusValue currentBolus;

    public TandemX2PumpSnapshot(long snapshotReceivedAtMillis,
            @Nullable IobValue controlIqIob,
            @Nullable IobValue nonControlIqIob,
            @Nullable BasalValue basal,
            @Nullable CurrentBolusValue currentBolus) {
        this.snapshotReceivedAtMillis = snapshotReceivedAtMillis;
        this.controlIqIob = controlIqIob;
        this.nonControlIqIob = nonControlIqIob;
        this.basal = basal;
        this.currentBolus = currentBolus;
    }

    public long getSnapshotReceivedAtMillis() { return snapshotReceivedAtMillis; }
    @Nullable public IobValue getControlIqIob() { return controlIqIob; }
    @Nullable public IobValue getNonControlIqIob() { return nonControlIqIob; }
    @Nullable public BasalValue getBasal() { return basal; }
    @Nullable public CurrentBolusValue getCurrentBolus() { return currentBolus; }

    public static final class IobValue {
        @Nullable private final Double units;
        private final String algorithm;
        private final long receivedAtMillis;

        public IobValue(@Nullable Double units, String algorithm, long receivedAtMillis) {
            this.units = units;
            this.algorithm = algorithm == null ? "UNKNOWN" : algorithm;
            this.receivedAtMillis = receivedAtMillis;
        }

        @Nullable public Double getUnits() { return units; }
        public String getAlgorithm() { return algorithm; }
        public long getReceivedAtMillis() { return receivedAtMillis; }
    }

    /** Raw response fields only; do not label as units/hour until validated. */
    public static final class BasalValue {
        private final long profileBasalRateRaw;
        private final long currentBasalRateRaw;
        private final int modifiedBitmask;
        private final long receivedAtMillis;

        public BasalValue(long profileBasalRateRaw, long currentBasalRateRaw,
                          int modifiedBitmask, long receivedAtMillis) {
            this.profileBasalRateRaw = profileBasalRateRaw;
            this.currentBasalRateRaw = currentBasalRateRaw;
            this.modifiedBitmask = modifiedBitmask;
            this.receivedAtMillis = receivedAtMillis;
        }

        public long getProfileBasalRateRaw() { return profileBasalRateRaw; }
        public long getCurrentBasalRateRaw() { return currentBasalRateRaw; }
        public int getModifiedBitmask() { return modifiedBitmask; }
        public long getReceivedAtMillis() { return receivedAtMillis; }
    }

    /** Current-bolus status only. This does not represent historical last-bolus data. */
    public static final class CurrentBolusValue {
        private final String status;
        @Nullable private final Double requestedUnits;
        @Nullable private final Integer bolusId;
        @Nullable private final Long pumpTimestampMillis;
        private final long receivedAtMillis;

        public CurrentBolusValue(String status, @Nullable Double requestedUnits,
                @Nullable Integer bolusId, @Nullable Long pumpTimestampMillis,
                long receivedAtMillis) {
            this.status = status == null ? "UNKNOWN" : status;
            this.requestedUnits = requestedUnits;
            this.bolusId = bolusId;
            this.pumpTimestampMillis = pumpTimestampMillis;
            this.receivedAtMillis = receivedAtMillis;
        }

        public String getStatus() { return status; }
        @Nullable public Double getRequestedUnits() { return requestedUnits; }
        @Nullable public Integer getBolusId() { return bolusId; }
        @Nullable public Long getPumpTimestampMillis() { return pumpTimestampMillis; }
        public long getReceivedAtMillis() { return receivedAtMillis; }
    }
}
