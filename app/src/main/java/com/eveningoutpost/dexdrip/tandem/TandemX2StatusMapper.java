package com.eveningoutpost.dexdrip.tandem;

import androidx.annotation.Nullable;

import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBasalStatusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.NonControlIQIOBResponse;

/** Pure response mapping. Does not connect to Bluetooth or calculate IOB from treatment history. */
public final class TandemX2StatusMapper {
    private TandemX2StatusMapper() {}

    @Nullable
    public static TandemX2PumpSnapshot.IobValue fromControlIqIob(
            @Nullable ControlIQIOBResponse response, long receivedAtMillis) {
        if (response == null) return null;

        ControlIQIOBResponse.IOBType type = response.getIOBType();
        if (type == null) {
            return new TandemX2PumpSnapshot.IobValue(null, "UNKNOWN", receivedAtMillis);
        }

        final long milliunits;
        switch (type) {
            case MUDALIAR:
                milliunits = response.getMudaliarIOB();
                break;
            case SWAN_6HR:
                milliunits = response.getSwan6hrIOB();
                break;
            default:
                return new TandemX2PumpSnapshot.IobValue(null, "UNKNOWN", receivedAtMillis);
        }
        return new TandemX2PumpSnapshot.IobValue(
                InsulinUnit.from1000To1(milliunits), type.name(), receivedAtMillis);
    }

    @Nullable
    public static TandemX2PumpSnapshot.IobValue fromNonControlIqIob(
            @Nullable NonControlIQIOBResponse response, long receivedAtMillis) {
        if (response == null) return null;
        return new TandemX2PumpSnapshot.IobValue(
                InsulinUnit.from1000To1(response.getIOB()), "NON_CONTROL_IQ", receivedAtMillis);
    }

    @Nullable
    public static TandemX2PumpSnapshot.BasalValue fromBasal(
            @Nullable CurrentBasalStatusResponse response, long receivedAtMillis) {
        if (response == null) return null;
        return new TandemX2PumpSnapshot.BasalValue(
                response.getProfileBasalRate(), response.getCurrentBasalRate(),
                response.getBasalModifiedBitmask(), receivedAtMillis);
    }

    @Nullable
    public static TandemX2PumpSnapshot.CurrentBolusValue fromCurrentBolus(
            @Nullable CurrentBolusStatusResponse response, long receivedAtMillis) {
        if (response == null) return null;
        if (!response.isValid()) {
            return new TandemX2PumpSnapshot.CurrentBolusValue(
                    "NO_CURRENT_BOLUS", null, null, null, receivedAtMillis);
        }

        CurrentBolusStatusResponse.CurrentBolusStatus status = response.getStatus();
        String statusName = status == null ? "UNKNOWN" : status.name();
        Double requestedUnits = InsulinUnit.from1000To1(response.getRequestedVolume());
        Long pumpTimestamp = response.getTimestampInstant() == null
                ? null : response.getTimestampInstant().toEpochMilli();

        return new TandemX2PumpSnapshot.CurrentBolusValue(
                statusName, requestedUnits, response.getBolusId(), pumpTimestamp, receivedAtMillis);
    }
}
