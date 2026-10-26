package com.eveningoutpost.dexdrip.garmin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eveningoutpost.dexdrip.tandem.TandemX2TestActivity;

/**
 * xDrip-Watch bridge test screen, opened from its "Garmin bridge test"
 * launcher icon. P1 sends a ping on open; P2 adds sending the saved G7 key.
 */
public class GarminBridgeTestActivity extends Activity {

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        final Button ping = new Button(this);
        ping.setText("Send ping");
        ping.setOnClickListener(v -> GarminBridge.sendTestMessage(this, this::post));
        layout.addView(ping);

        // Sends a secret to the watch: confirm with a second tap.
        final Button key = new Button(this);
        key.setText("Send G7 key to watch");
        key.setOnClickListener(v -> {
            if (key.getTag() == null) {
                key.setTag(Boolean.TRUE);
                key.setText("Tap again to send the key");
            } else {
                key.setTag(null);
                key.setText("Send G7 key to watch");
                GarminBridge.sendKey(this, this::post);
            }
        });
        layout.addView(key);

        final Button listen = new Button(this);
        listen.setText("Listen for watch glucose");
        listen.setOnClickListener(v -> {
            append("Last watch glucose: " + GarminBridge.lastWatchGlucose);
            GarminBridge.startListening(this, this::post);
        });
        layout.addView(listen);

        final Button tandem = new Button(this);
        tandem.setText("Tandem pump test");
        tandem.setOnClickListener(v -> startActivity(new Intent(this, TandemX2TestActivity.class)));
        layout.addView(tandem);

        output = new TextView(this);
        output.setTextIsSelectable(true);
        layout.addView(output);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        append("Garmin bridge test, watch app " + GarminBridge.WATCH_APP_ID);
        GarminBridge.sendTestMessage(this, this::post);
    }

    private void post(String line) {
        runOnUiThread(() -> append(line));
    }

    private void append(String line) {
        output.append(line + "\n");
    }
}
