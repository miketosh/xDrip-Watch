package com.eveningoutpost.dexdrip.garmin;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * xDrip-Watch P1: sends one test message to the G7 Scout watch app and shows
 * the SDK's status lines. Opened from its "Garmin bridge test" launcher icon.
 */
public class GarminBridgeTestActivity extends Activity {

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setPadding(32, 32, 32, 32);
        output.setTextIsSelectable(true);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);
        append("Garmin bridge test, watch app " + GarminBridge.WATCH_APP_ID);
        GarminBridge.sendTestMessage(this, line -> runOnUiThread(() -> append(line)));
    }

    private void append(String line) {
        output.append(line + "\n");
    }
}
