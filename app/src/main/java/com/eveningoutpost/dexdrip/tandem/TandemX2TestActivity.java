package com.eveningoutpost.dexdrip.tandem;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eveningoutpost.dexdrip.garmin.GarminBridge;
import com.eveningoutpost.dexdrip.garmin.GarminWatchMessages;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * xDrip-Watch: supervised Tandem t:slim X2 test screen, opened from the Garmin bridge test
 * screen. Shows connection states and snapshot values on screen only; nothing is written to
 * xDrip's logs or database. The pairing code is never echoed.
 */
public class TandemX2TestActivity extends Activity implements TandemX2PumpClient.Listener {

    private static final int[] MODE_IDS = {1, 2, 3};
    private static final TandemX2ConnectionMode[] MODES = {
            TandemX2ConnectionMode.LISTEN_ONLY,
            TandemX2ConnectionMode.SHARED_READS,
            TandemX2ConnectionMode.OWN_PAIRING};

    static final String PREF_BOLUS_ENABLED = "tandem_x2_experimental_bolus_enabled";
    static final String PREF_BOLUS_CAP = "tandem_x2_experimental_bolus_cap_units";

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private Button deliver;
    private TandemX2BolusSession bolusSession;
    private TandemX2PumpSnapshot lastSnapshot;
    private String link = GarminWatchMessages.LINK_OFF;
    private int watchSeq;
    private EditText mac;
    private RadioGroup modes;
    private EditText pairingCode;
    private TextView output;
    private TandemX2PumpClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        final TextView intro = new TextView(this);
        intro.setText("Tandem t:slim X2 test. Listen only sends nothing to the pump and keeps "
                + "the Tandem app connected. Own pairing displaces the Tandem app: re-pair it "
                + "afterwards. The experimental bolus below works only in own pairing, in "
                + "non-release builds, when switched on; nothing else can change therapy.");
        layout.addView(intro);

        mac = new EditText(this);
        mac.setHint("Pump Bluetooth address (AA:BB:CC:DD:EE:FF)");
        mac.setSingleLine(true);
        layout.addView(mac);
        addBondedPumps(layout);

        modes = new RadioGroup(this);
        final String[] labels = {"Listen only (Tandem app stays connected)",
                "Shared reads (Tandem app authenticates; xDrip sends status reads)",
                "Own pairing (displaces the Tandem app)"};
        for (int i = 0; i < MODES.length; i++) {
            final RadioButton b = new RadioButton(this);
            b.setId(MODE_IDS[i]);
            b.setText(labels[i]);
            modes.addView(b);
        }
        modes.check(MODE_IDS[0]);
        layout.addView(modes);

        // Own pairing disconnects the Tandem app: confirm with a second tap.
        final Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> {
            final TandemX2ConnectionMode mode = selectedMode();
            if (mode == TandemX2ConnectionMode.OWN_PAIRING && start.getTag() == null) {
                start.setTag(Boolean.TRUE);
                start.setText("Tap again: pair xDrip and displace the Tandem app");
                return;
            }
            start.setTag(null);
            start.setText("Start");
            startClient(mode);
        });
        layout.addView(start);

        final Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopClient());
        layout.addView(stop);

        pairingCode = new EditText(this);
        pairingCode.setHint("Pairing code shown on the pump (own pairing only)");
        pairingCode.setSingleLine(true);
        pairingCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        layout.addView(pairingCode);

        final Button pair = new Button(this);
        pair.setText("Submit pairing code");
        pair.setOnClickListener(v -> {
            if (client == null) {
                append("Start own pairing first");
                return;
            }
            final boolean sent = client.submitPairingCode(pairingCode.getText().toString());
            pairingCode.setText("");
            append(sent ? "Pairing code submitted" : "Pairing code not accepted (no pairing pending, or malformed)");
        });
        layout.addView(pair);

        final Button toWatch = new Button(this);
        toWatch.setText("Send pump status to watch");
        toWatch.setOnClickListener(v -> GarminBridge.sendPumpStatus(this,
                GarminWatchMessages.pumpStatus(lastSnapshot, link, ++watchSeq, System.currentTimeMillis()),
                this::append));
        layout.addView(toWatch);

        addBolusSection(layout);

        output = new TextView(this);
        output.setTextIsSelectable(true);
        layout.addView(output);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private void addBolusSection(LinearLayout layout) {
        final TextView title = new TextView(this);
        title.setText("\nExperimental bolus (owner-approved test; check every result on the pump)");
        layout.addView(title);
        if (TandemX2PumpClient.isReleaseBuild()) {
            final TextView off = new TextView(this);
            off.setText("Not available in release builds.");
            layout.addView(off);
            return;
        }

        final CheckBox enable = new CheckBox(this);
        enable.setText("Enable experimental bolus");
        enable.setChecked(Pref.getBooleanDefaultFalse(PREF_BOLUS_ENABLED));
        enable.setOnCheckedChangeListener((v, checked) -> Pref.setBoolean(PREF_BOLUS_ENABLED, checked));
        layout.addView(enable);

        final EditText cap = new EditText(this);
        cap.setHint("Bolus cap in units (0.05 to 25)");
        cap.setText(TandemX2BolusPolicy.formatUnits(capMilliunits()));
        cap.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(cap);
        final Button saveCap = new Button(this);
        saveCap.setText("Save cap");
        saveCap.setOnClickListener(v -> {
            final Long milli = TandemX2BolusPolicy.parseMilliunits(cap.getText().toString());
            if (milli == null || milli != TandemX2BolusPolicy.sanitizeCap(milli)) {
                append("Cap not saved: use 0.05 to 25 U with at most two decimals");
                return;
            }
            Pref.setString(PREF_BOLUS_CAP, TandemX2BolusPolicy.formatUnits(milli));
            append("Bolus cap " + TandemX2BolusPolicy.formatUnits(milli) + " U");
        });
        layout.addView(saveCap);

        final EditText amount = new EditText(this);
        amount.setHint("Bolus amount in units");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(amount);

        final Button request = new Button(this);
        request.setText("Request bolus (reads limits, asks permission; delivers nothing)");
        request.setOnClickListener(v -> {
            if (bolusSession == null) {
                append("Start own pairing first");
                return;
            }
            final TandemX2BolusPolicy.Refusal r = bolusSession.request(
                    TandemX2BolusPolicy.parseMilliunits(amount.getText().toString()), System.currentTimeMillis());
            if (r != TandemX2BolusPolicy.Refusal.NONE) append("Bolus refused: " + r.text);
        });
        layout.addView(request);

        deliver = new Button(this);
        deliver.setEnabled(false);
        deliver.setText("Deliver (after permission)");
        deliver.setOnClickListener(v -> {
            if (bolusSession != null) bolusSession.confirm(System.currentTimeMillis());
        });
        layout.addView(deliver);

        final Button cancel = new Button(this);
        cancel.setText("Cancel bolus");
        cancel.setOnClickListener(v -> {
            if (bolusSession != null) bolusSession.cancel(System.currentTimeMillis());
        });
        layout.addView(cancel);

        final Button checked = new Button(this);
        checked.setText("I checked the pump's history (after an unknown result)");
        checked.setOnClickListener(v -> {
            if (bolusSession != null) bolusSession.acknowledge();
        });
        layout.addView(checked);
    }

    private long capMilliunits() {
        final Long milli = TandemX2BolusPolicy.parseMilliunits(Pref.getString(PREF_BOLUS_CAP,
                TandemX2BolusPolicy.formatUnits(TandemX2BolusPolicy.DEFAULT_CAP_MILLIUNITS)));
        return TandemX2BolusPolicy.sanitizeCap(milli == null ? TandemX2BolusPolicy.DEFAULT_CAP_MILLIUNITS : milli);
    }

    private TandemX2BolusSession newBolusSession(TandemX2PumpClient c) {
        return new TandemX2BolusSession(new TandemX2BolusSession.Gates() {
            @Override public boolean isReleaseBuild() { return TandemX2PumpClient.isReleaseBuild(); }
            @Override public boolean isEnabled() { return Pref.getBooleanDefaultFalse(PREF_BOLUS_ENABLED); }
            @Override public TandemX2ConnectionMode mode() { return c.getMode(); }
            @Override public long capMilliunits() { return TandemX2TestActivity.this.capMilliunits(); }
        }, c::sendBolusStep, (state, detail) -> {
            append("BOLUS " + state + ": " + detail);
            runOnUiThread(() -> {
                if (deliver == null) return;
                final boolean ready = state == TandemX2BolusSession.State.READY_TO_CONFIRM;
                deliver.setEnabled(ready);
                deliver.setText(ready ? "Deliver " + TandemX2BolusPolicy.formatUnits(
                        bolusSession.getMilliunits()) + " U now" : "Deliver (after permission)");
            });
        });
    }

    /** About once a second: bolus timeouts, and bolus progress while delivering. */
    private final Runnable bolusTicker = new Runnable() {
        private int ticks;

        @Override
        public void run() {
            final TandemX2BolusSession s = bolusSession;
            if (s != null) {
                s.tick(System.currentTimeMillis());
                if (s.getState() == TandemX2BolusSession.State.DELIVERING && client != null && ++ticks % 3 == 0) {
                    client.requestCurrentBolusStatus();
                }
            }
            ticker.postDelayed(this, 1000);
        }
    };

    private void addBondedPumps(LinearLayout layout) {
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            final Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice device : bonded) {
                final String name = device.getName();
                if (name == null) continue;
                final String lower = name.toLowerCase(Locale.US);
                if (!lower.contains("tslim") && !lower.contains("mobi")) continue;
                final Button b = new Button(this);
                b.setText("Use " + name + " (" + device.getAddress() + ")");
                b.setOnClickListener(v -> mac.setText(device.getAddress()));
                layout.addView(b);
            }
        } catch (SecurityException e) {
            final TextView t = new TextView(this);
            t.setText("Bluetooth permission missing; enter the address by hand");
            layout.addView(t);
        }
    }

    private TandemX2ConnectionMode selectedMode() {
        final int id = modes.getCheckedRadioButtonId();
        for (int i = 0; i < MODE_IDS.length; i++) {
            if (MODE_IDS[i] == id) return MODES[i];
        }
        return TandemX2ConnectionMode.LISTEN_ONLY;
    }

    private void startClient(TandemX2ConnectionMode mode) {
        stopClient();
        try {
            client = new TandemX2PumpClient(this, mac.getText().toString(), mode, this);
        } catch (IllegalArgumentException e) {
            append(e.getMessage());
            return;
        }
        append("Starting " + mode + " for " + client.getTargetMac());
        bolusSession = newBolusSession(client);
        client.setBolusSession(bolusSession);
        client.start();
    }

    private void stopClient() {
        final TandemX2BolusSession s = bolusSession;
        if (s != null && (s.getState() == TandemX2BolusSession.State.SENDING
                || s.getState() == TandemX2BolusSession.State.DELIVERING
                || s.getState() == TandemX2BolusSession.State.CANCELLING
                || s.getState() == TandemX2BolusSession.State.UNKNOWN)) {
            append("A bolus may still be running: use the pump to check or cancel it");
        }
        if (client != null) {
            client.setBolusSession(null);
            client.stop();
            client = null;
        }
        bolusSession = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(bolusTicker);
        ticker.post(bolusTicker);
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacks(bolusTicker);
        stopClient();
        super.onDestroy();
    }

    @Override
    public void onConnectionStateChanged(TandemX2PumpClient.ConnectionState state, String detail) {
        if (state == TandemX2PumpClient.ConnectionState.CONNECTED) {
            link = GarminWatchMessages.LINK_UP;
        } else if (state == TandemX2PumpClient.ConnectionState.DISCONNECTED) {
            link = GarminWatchMessages.LINK_DOWN;
        } else if (state == TandemX2PumpClient.ConnectionState.STOPPED) {
            link = GarminWatchMessages.LINK_OFF;
        }
        append(state + ": " + detail);
    }

    @Override
    public void onSnapshot(TandemX2PumpSnapshot s) {
        lastSnapshot = s;
        final StringBuilder b = new StringBuilder("Snapshot");
        appendIob(b, "Control-IQ IOB", s.getControlIqIob());
        appendIob(b, "IOB", s.getNonControlIqIob());
        if (s.getBasal() != null) {
            b.append("\n  basal raw: profile ").append(s.getBasal().getProfileBasalRateRaw())
                    .append(", current ").append(s.getBasal().getCurrentBasalRateRaw())
                    .append(", modified bitmask ").append(s.getBasal().getModifiedBitmask());
        }
        if (s.getCurrentBolus() != null) {
            b.append("\n  current bolus: ").append(s.getCurrentBolus().getStatus());
            if (s.getCurrentBolus().getRequestedUnits() != null) {
                b.append(' ').append(s.getCurrentBolus().getRequestedUnits()).append(" U requested");
            }
        }
        append(b.toString());
    }

    private static void appendIob(StringBuilder b, String label, TandemX2PumpSnapshot.IobValue v) {
        if (v == null) return;
        b.append("\n  ").append(label).append(" (").append(v.getAlgorithm()).append("): ")
                .append(v.getUnits() == null ? "unknown" : v.getUnits() + " U");
    }

    @Override
    public void onQualifyingEvents(Set<QualifyingEvent> events) {
        append("Pump events: " + events);
    }

    private void append(String line) {
        runOnUiThread(() -> output.append(clock.format(new Date()) + " " + line + "\n"));
    }
}
