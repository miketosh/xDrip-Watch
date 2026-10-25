package com.eveningoutpost.dexdrip.tandem;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
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
                + "afterwards. No mode can bolus or change therapy.");
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

        output = new TextView(this);
        output.setTextIsSelectable(true);
        layout.addView(output);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

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
        client.start();
    }

    private void stopClient() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopClient();
        super.onDestroy();
    }

    @Override
    public void onConnectionStateChanged(TandemX2PumpClient.ConnectionState state, String detail) {
        append(state + ": " + detail);
    }

    @Override
    public void onSnapshot(TandemX2PumpSnapshot s) {
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
