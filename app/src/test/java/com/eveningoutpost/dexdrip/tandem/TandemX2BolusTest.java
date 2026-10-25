package com.eveningoutpost.dexdrip.tandem;

import static com.eveningoutpost.dexdrip.tandem.TandemX2BolusPolicy.Refusal;
import static com.eveningoutpost.dexdrip.tandem.TandemX2BolusSession.State;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionReleaseRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BolusCalcDataSnapshotRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest;
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.BolusCalcDataSnapshotResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Every gate of the experimental bolus (xDrip-Watch AGENTS.md, owner decision 2026-10-25). */
public class TandemX2BolusTest {

    private static final int BOLUS_ID = 4242;

    private boolean releaseBuild;
    private boolean enabled;
    private TandemX2ConnectionMode mode;
    private long cap;
    private boolean senderUp;
    private final List<Message> sent = new ArrayList<>();
    private TandemX2BolusSession session;

    @Before
    public void setUp() {
        releaseBuild = false;
        enabled = true;
        mode = TandemX2ConnectionMode.OWN_PAIRING;
        cap = TandemX2BolusPolicy.DEFAULT_CAP_MILLIUNITS;
        senderUp = true;
        sent.clear();
        session = new TandemX2BolusSession(new TandemX2BolusSession.Gates() {
            @Override public boolean isReleaseBuild() { return releaseBuild; }
            @Override public boolean isEnabled() { return enabled; }
            @Override public TandemX2ConnectionMode mode() { return mode; }
            @Override public long capMilliunits() { return cap; }
        }, m -> {
            if (!senderUp) return false;
            sent.add(m);
            return true;
        }, null);
    }

    // --- policy ---

    @Test
    public void amountsParseToMilliunits() {
        assertEquals(Long.valueOf(1250), TandemX2BolusPolicy.parseMilliunits("1.25"));
        assertEquals(Long.valueOf(500), TandemX2BolusPolicy.parseMilliunits(".5"));
        assertEquals(Long.valueOf(3000), TandemX2BolusPolicy.parseMilliunits(" 3 "));
        assertEquals(Long.valueOf(50), TandemX2BolusPolicy.parseMilliunits("0.05"));
        assertNull(TandemX2BolusPolicy.parseMilliunits("1.255"));
        assertNull(TandemX2BolusPolicy.parseMilliunits("-1"));
        assertNull(TandemX2BolusPolicy.parseMilliunits("1e3"));
        assertNull(TandemX2BolusPolicy.parseMilliunits("100"));
        assertNull(TandemX2BolusPolicy.parseMilliunits(""));
        assertNull(TandemX2BolusPolicy.parseMilliunits(null));
    }

    @Test
    public void capDefaultsToFiveUnitsAndCannotBeWidened() {
        assertEquals(5_000, TandemX2BolusPolicy.DEFAULT_CAP_MILLIUNITS);
        assertEquals(2_000, TandemX2BolusPolicy.sanitizeCap(2_000));
        assertEquals(25_000, TandemX2BolusPolicy.sanitizeCap(25_000));
        assertEquals(5_000, TandemX2BolusPolicy.sanitizeCap(30_000));
        assertEquals(5_000, TandemX2BolusPolicy.sanitizeCap(0));
    }

    @Test
    public void everyGateRefuses() {
        final TandemX2ConnectionMode own = TandemX2ConnectionMode.OWN_PAIRING;
        assertEquals(Refusal.RELEASE_BUILD, TandemX2BolusPolicy.check(true, true, own, 1000L, 5000, null));
        assertEquals(Refusal.NOT_ENABLED, TandemX2BolusPolicy.check(false, false, own, 1000L, 5000, null));
        assertEquals(Refusal.NOT_OWN_PAIRING, TandemX2BolusPolicy.check(false, true, TandemX2ConnectionMode.LISTEN_ONLY, 1000L, 5000, null));
        assertEquals(Refusal.NOT_OWN_PAIRING, TandemX2BolusPolicy.check(false, true, TandemX2ConnectionMode.SHARED_READS, 1000L, 5000, null));
        assertEquals(Refusal.INVALID_AMOUNT, TandemX2BolusPolicy.check(false, true, own, null, 5000, null));
        assertEquals(Refusal.BELOW_MINIMUM, TandemX2BolusPolicy.check(false, true, own, 40L, 5000, null));
        assertEquals(Refusal.ABOVE_CAP, TandemX2BolusPolicy.check(false, true, own, 5010L, 5000, null));
        assertEquals(Refusal.ABOVE_PUMP_MAXIMUM, TandemX2BolusPolicy.check(false, true, own, 3000L, 5000, 2500L));
        assertEquals(Refusal.NONE, TandemX2BolusPolicy.check(false, true, own, 5000L, 5000, 25000L));
    }

    @Test
    public void bolusStepsAreNotStatusReads() {
        assertTrue(TandemX2BolusPolicy.isBolusStep(new InitiateBolusRequest()));
        assertTrue(TandemX2BolusPolicy.isBolusStep(new BolusPermissionRequest()));
        assertFalse(TandemX2BolusPolicy.isBolusStep(new CurrentBasalStatusRequest()));
        for (TandemX2ConnectionMode m : TandemX2ConnectionMode.values()) {
            assertFalse(TandemX2RequestPolicy.isAllowed(m, new InitiateBolusRequest()));
            assertFalse(TandemX2RequestPolicy.isAllowed(m, new BolusPermissionRequest()));
        }
    }

    // --- session ---

    @Test
    public void happyPathSendsExactlyTheCapturedUnitsOnlyBolus() {
        assertEquals(Refusal.NONE, session.request(1250L, 0));
        assertTrue(sent.get(0) instanceof BolusCalcDataSnapshotRequest);
        session.onSnapshot(snapshot(25_000), 100);
        assertTrue(sent.get(1) instanceof BolusPermissionRequest);
        session.onPermission(granted(), 200);
        assertEquals(State.READY_TO_CONFIRM, session.getState());
        assertEquals(2, sent.size());

        assertTrue(session.confirm(300));
        final InitiateBolusRequest initiate = (InitiateBolusRequest) sent.get(2);
        assertEquals(1250, initiate.getTotalVolume());
        assertEquals(BOLUS_ID, initiate.getBolusID());
        assertEquals(TandemX2BolusPolicy.UNITS_ONLY_BOLUS_TYPE, initiate.getBolusTypeBitmask());
        assertEquals(0, initiate.getFoodVolume());
        assertEquals(0, initiate.getCorrectionVolume());
        assertEquals(0, initiate.getBolusCarbs());
        assertEquals(0, initiate.getBolusBG());
        assertEquals(0, initiate.getBolusIOB());

        session.onInitiate(new InitiateBolusResponse(0, BOLUS_ID, 0));
        assertEquals(State.DELIVERING, session.getState());
        session.onCurrentBolus(new CurrentBolusStatusResponse(0, 0, 0L, 0L, 0, 0));
        assertEquals(State.DONE, session.getState());
        assertEquals(3, sent.size());
    }

    @Test
    public void refusedRequestsSendNothing() {
        cap = 1_000;
        assertEquals(Refusal.ABOVE_CAP, session.request(1_500L, 0));
        enabled = false;
        assertEquals(Refusal.NOT_ENABLED, session.request(500L, 0));
        enabled = true;
        mode = TandemX2ConnectionMode.LISTEN_ONLY;
        assertEquals(Refusal.NOT_OWN_PAIRING, session.request(500L, 0));
        mode = TandemX2ConnectionMode.OWN_PAIRING;
        releaseBuild = true;
        assertEquals(Refusal.RELEASE_BUILD, session.request(500L, 0));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void pumpMaximumStopsBeforePermission() {
        session.request(3_000L, 0);
        session.onSnapshot(snapshot(2_000), 100);
        assertEquals(State.REFUSED, session.getState());
        assertEquals(1, sent.size());
    }

    @Test
    public void permissionDeniedDeliversNothing() {
        session.request(1_000L, 0);
        session.onSnapshot(snapshot(25_000), 100);
        session.onPermission(new BolusPermissionResponse(1, 0, 1), 200);
        assertEquals(State.REFUSED, session.getState());
        assertFalse(session.confirm(300));
        assertFalse(sent.stream().anyMatch(m -> m instanceof InitiateBolusRequest));
    }

    @Test
    public void expiredConfirmationReleasesPermissionInsteadOfDelivering() {
        readyToConfirm(1_000L);
        assertFalse(session.confirm(200 + TandemX2BolusSession.CONFIRM_WINDOW_MS + 1));
        assertTrue(sent.get(sent.size() - 1) instanceof BolusPermissionReleaseRequest);
        assertFalse(sent.stream().anyMatch(m -> m instanceof InitiateBolusRequest));
    }

    @Test
    public void tickExpiresAnUnconfirmedPermission() {
        readyToConfirm(1_000L);
        session.tick(200 + TandemX2BolusSession.CONFIRM_WINDOW_MS + 1);
        assertEquals(State.REFUSED, session.getState());
        assertTrue(sent.get(sent.size() - 1) instanceof BolusPermissionReleaseRequest);
    }

    @Test
    public void settingsChangedBeforeConfirmAreHonoured() {
        readyToConfirm(2_000L);
        cap = 1_000;
        assertFalse(session.confirm(300));
        assertTrue(sent.get(sent.size() - 1) instanceof BolusPermissionReleaseRequest);
        assertFalse(sent.stream().anyMatch(m -> m instanceof InitiateBolusRequest));
    }

    @Test
    public void secondConfirmSendsNoSecondBolus() {
        readyToConfirm(1_000L);
        assertTrue(session.confirm(300));
        assertFalse(session.confirm(301));
        assertEquals(1, sent.stream().filter(m -> m instanceof InitiateBolusRequest).count());
    }

    @Test
    public void unansweredInitiateIsUnknownNeverResentAndBlocksNewRequests() {
        readyToConfirm(1_000L);
        session.confirm(300);
        session.tick(300 + TandemX2BolusSession.RESPONSE_TIMEOUT_MS + 1);
        assertEquals(State.UNKNOWN, session.getState());
        session.tick(300 + 10 * TandemX2BolusSession.RESPONSE_TIMEOUT_MS);
        assertEquals(1, sent.stream().filter(m -> m instanceof InitiateBolusRequest).count());

        assertEquals(Refusal.BUSY, session.request(1_000L, 50_000));
        session.acknowledge();
        assertEquals(Refusal.NONE, session.request(1_000L, 60_000));
    }

    @Test
    public void cancelBeforeConfirmReleasesAfterConfirmCancels() {
        readyToConfirm(1_000L);
        session.cancel(300);
        assertTrue(sent.get(sent.size() - 1) instanceof BolusPermissionReleaseRequest);

        setUp();
        readyToConfirm(1_000L);
        session.confirm(300);
        session.onInitiate(new InitiateBolusResponse(0, BOLUS_ID, 0));
        session.cancel(400);
        final CancelBolusRequest cancel = (CancelBolusRequest) sent.get(sent.size() - 1);
        assertEquals(BOLUS_ID, cancel.getBolusId());
        session.onCancel(new CancelBolusResponse(0, BOLUS_ID, 0));
        assertEquals(State.DONE, session.getState());
    }

    @Test
    public void latePermissionAfterTimeoutIsReleased() {
        session.request(1_000L, 0);
        session.onSnapshot(snapshot(25_000), 100);
        session.tick(100 + TandemX2BolusSession.RESPONSE_TIMEOUT_MS + 1);
        assertEquals(State.REFUSED, session.getState());
        session.onPermission(granted(), 20_000);
        assertTrue(sent.get(sent.size() - 1) instanceof BolusPermissionReleaseRequest);
        assertFalse(session.confirm(20_001));
    }

    @Test
    public void responsesForAnotherBolusAreIgnored() {
        readyToConfirm(1_000L);
        session.confirm(300);
        session.onInitiate(new InitiateBolusResponse(0, BOLUS_ID + 1, 0));
        assertEquals(State.SENDING, session.getState());
    }

    @Test
    public void senderDownRefusesWithoutDelivering() {
        senderUp = false;
        assertEquals(Refusal.NOT_CONNECTED, session.request(1_000L, 0));
        assertEquals(State.REFUSED, session.getState());
    }

    private void readyToConfirm(long milliunits) {
        session.request(milliunits, 0);
        session.onSnapshot(snapshot(25_000), 100);
        session.onPermission(granted(), 200);
        assertEquals(State.READY_TO_CONFIRM, session.getState());
    }

    private static BolusPermissionResponse granted() {
        return new BolusPermissionResponse(0, BOLUS_ID, 0);
    }

    private static BolusCalcDataSnapshotResponse snapshot(int maxBolusMilliunits) {
        return new BolusCalcDataSnapshotResponse(false, 0, 0, 100, 110, 50, true, 10_000,
                maxBolusMilliunits, 0, false, false, false);
    }
}
