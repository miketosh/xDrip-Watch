package com.eveningoutpost.dexdrip.utils.framework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * xDrip-Watch: runtime receiver registration for target SDK 34+.
 *
 * Android 14 throws SecurityException when an app targeting 34+ registers a
 * receiver for non-system broadcasts without RECEIVER_EXPORTED or
 * RECEIVER_NOT_EXPORTED. xDrip receives broadcasts from other apps
 * (Nightscout emulation, external status, watch communication), so every
 * registration keeps the previous behaviour: exported.
 */
public final class Receivers {

    private Receivers() {
    }

    public static Intent register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        }
        return context.registerReceiver(receiver, filter);
    }
}
