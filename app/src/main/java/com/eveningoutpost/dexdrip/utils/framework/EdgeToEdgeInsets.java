package com.eveningoutpost.dexdrip.utils.framework;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * xDrip-Watch: Android 15 draws apps targeting 35+ edge to edge, and from
 * target 36 apps cannot opt out. xDrip's screens were laid out for the old
 * behaviour (the toolbar ended up under the status bar). This pads every
 * activity's root view by the system bar and cutout insets, which restores
 * the old layout without changing each screen.
 */
public final class EdgeToEdgeInsets implements Application.ActivityLifecycleCallbacks {

    public static void register(Application application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            application.registerActivityLifecycleCallbacks(new EdgeToEdgeInsets());
        }
    }

    @Override
    public void onActivityPostCreated(Activity activity, Bundle savedInstanceState) {
        padRoot(activity.getWindow().getDecorView());
    }

    /**
     * A nested PreferenceScreen opens as a Dialog, which the activity callbacks never see: its
     * toolbar covered the first rows (the Garmin watch switch could not be tapped, 2026-10-26).
     */
    public static void padDialog(Dialog dialog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || dialog == null || dialog.getWindow() == null) {
            return;
        }
        padRoot(dialog.getWindow().getDecorView());
    }

    private static void padRoot(View decor) {
        if (!(decor instanceof ViewGroup) || ((ViewGroup) decor).getChildCount() == 0) {
            return;
        }
        final View root = ((ViewGroup) decor).getChildAt(0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
