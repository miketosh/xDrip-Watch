package com.eveningoutpost.dexdrip.tandem;

import static com.eveningoutpost.dexdrip.tandem.TandemX2ConnectionMode.LISTEN_ONLY;
import static com.eveningoutpost.dexdrip.tandem.TandemX2ConnectionMode.OWN_PAIRING;
import static com.eveningoutpost.dexdrip.tandem.TandemX2ConnectionMode.SHARED_READS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.request.authentication.CentralChallengeRequest;
import com.jwoglom.pumpx2.pump.messages.request.authentication.Jpake1aRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.SetTempRateRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.SuspendPumpingRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ApiVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogRequest;

import org.junit.Test;

import timber.log.Timber;

public class TandemX2RequestPolicyTest {

    private static final Message[] STATUS_READS = {
            new ApiVersionRequest(), new ControlIQIOBRequest(), new CurrentBasalStatusRequest()};
    private static final Message[] AUTH = {new CentralChallengeRequest(), new Jpake1aRequest()};
    private static final Message[] THERAPY = {
            new InitiateBolusRequest(), new CancelBolusRequest(), new SuspendPumpingRequest(),
            new SetTempRateRequest()};

    @Test
    public void listenOnlySendsNothing() {
        for (Message m : STATUS_READS) assertFalse(m.toString(), TandemX2RequestPolicy.isAllowed(LISTEN_ONLY, m));
        for (Message m : AUTH) assertFalse(m.toString(), TandemX2RequestPolicy.isAllowed(LISTEN_ONLY, m));
    }

    @Test
    public void sharedReadsSendsStatusReadsButNeverAuthentication() {
        for (Message m : STATUS_READS) assertTrue(m.toString(), TandemX2RequestPolicy.isAllowed(SHARED_READS, m));
        for (Message m : AUTH) assertFalse(m.toString(), TandemX2RequestPolicy.isAllowed(SHARED_READS, m));
    }

    @Test
    public void ownPairingSendsAuthenticationAndStatusReads() {
        for (Message m : STATUS_READS) assertTrue(m.toString(), TandemX2RequestPolicy.isAllowed(OWN_PAIRING, m));
        for (Message m : AUTH) assertTrue(m.toString(), TandemX2RequestPolicy.isAllowed(OWN_PAIRING, m));
    }

    @Test
    public void therapyCommandsAreBlockedInEveryMode() {
        for (TandemX2ConnectionMode mode : TandemX2ConnectionMode.values()) {
            for (Message m : THERAPY) assertFalse(mode + " " + m, TandemX2RequestPolicy.isAllowed(mode, m));
        }
    }

    @Test
    public void historyIsNotAllowlistedYet() {
        for (TandemX2ConnectionMode mode : TandemX2ConnectionMode.values()) {
            assertFalse(TandemX2RequestPolicy.isAllowed(mode, new HistoryLogRequest()));
        }
    }

    @Test
    public void nullModeOrMessageIsBlocked() {
        assertFalse(TandemX2RequestPolicy.isAllowed(null, new ApiVersionRequest()));
        assertFalse(TandemX2RequestPolicy.isAllowed(OWN_PAIRING, null));
    }

    @Test
    public void pairingCodesAreNormalizedOrRejected() {
        assertEquals("123456", TandemX2RequestPolicy.normalizePairingCode("123456"));
        assertEquals("123456", TandemX2RequestPolicy.normalizePairingCode(" 123 456 "));
        assertEquals("abcdefghijklmnop", TandemX2RequestPolicy.normalizePairingCode("abcd-efgh-ijkl-mnop"));
        assertNull(TandemX2RequestPolicy.normalizePairingCode("12345"));
        assertNull(TandemX2RequestPolicy.normalizePairingCode("1234567"));
        assertNull(TandemX2RequestPolicy.normalizePairingCode(""));
        assertNull(TandemX2RequestPolicy.normalizePairingCode(null));
    }

    @Test
    public void hexSecretDecodesOrIsRejected() {
        assertArrayEquals(new byte[]{0x01, (byte) 0xab, (byte) 0xff},
                TandemX2RequestPolicy.hexToBytes("01abFF"));
        assertNull(TandemX2RequestPolicy.hexToBytes(""));
        assertNull(TandemX2RequestPolicy.hexToBytes("abc"));
        assertNull(TandemX2RequestPolicy.hexToBytes("zz"));
        assertNull(TandemX2RequestPolicy.hexToBytes(null));
    }

    @Test
    public void loggingGuardFailsClosedWhenATreeIsPlanted() {
        Timber.uprootAll();
        assertTrue(TandemX2RequestPolicy.isPumpX2LoggingSilent());
        final Timber.Tree tree = new Timber.Tree() {
            @Override
            protected void log(int priority, String tag, String message, Throwable t) {}
        };
        Timber.plant(tree);
        try {
            assertFalse(TandemX2RequestPolicy.isPumpX2LoggingSilent());
        } finally {
            Timber.uproot(tree);
        }
        assertTrue(TandemX2RequestPolicy.isPumpX2LoggingSilent());
    }
}
