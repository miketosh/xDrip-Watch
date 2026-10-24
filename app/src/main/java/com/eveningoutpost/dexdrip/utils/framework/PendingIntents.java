package com.eveningoutpost.dexdrip.utils.framework;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * xDrip-Watch: PendingIntent creation for target SDK 31+.
 *
 * Android 12 throws IllegalArgumentException for a PendingIntent created
 * without FLAG_IMMUTABLE or FLAG_MUTABLE. Every call site goes through here;
 * the plain methods add FLAG_IMMUTABLE when neither flag is set. The mutable
 * methods are for PendingIntents the system fills in (Bluetooth background
 * scan results, the USB permission answer, activity recognition); since
 * Android 14 those must be explicit, so they get this app's package if the
 * intent has no component.
 */
public final class PendingIntents {

    private static final int MUTABILITY = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE;

    private PendingIntents() {
    }

    static int immutableUnlessSpecified(final int flags) {
        return (flags & MUTABILITY) == 0 ? flags | PendingIntent.FLAG_IMMUTABLE : flags;
    }

    public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getActivity(context, requestCode, intent, immutableUnlessSpecified(flags));
    }

    public static PendingIntent getActivities(Context context, int requestCode, Intent[] intents, int flags) {
        return PendingIntent.getActivities(context, requestCode, intents, immutableUnlessSpecified(flags));
    }

    public static PendingIntent getService(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getService(context, requestCode, intent, immutableUnlessSpecified(flags));
    }

    public static PendingIntent getForegroundService(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getForegroundService(context, requestCode, intent, immutableUnlessSpecified(flags));
    }

    public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getBroadcast(context, requestCode, intent, immutableUnlessSpecified(flags));
    }

    public static PendingIntent getMutableBroadcast(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getBroadcast(context, requestCode, explicit(context, intent), (flags & ~PendingIntent.FLAG_IMMUTABLE) | PendingIntent.FLAG_MUTABLE);
    }

    public static PendingIntent getMutableService(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getService(context, requestCode, explicit(context, intent), (flags & ~PendingIntent.FLAG_IMMUTABLE) | PendingIntent.FLAG_MUTABLE);
    }

    private static Intent explicit(Context context, Intent intent) {
        if (intent.getComponent() == null && intent.getPackage() == null) {
            intent.setPackage(context.getPackageName());
        }
        return intent;
    }
}
