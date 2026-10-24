package com.eveningoutpost.dexdrip.utils.framework;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.eveningoutpost.dexdrip.models.UserError;

import java.util.ArrayList;
import java.util.List;

/**
 * xDrip-Watch: runtime permissions that only exist for apps targeting newer
 * Android versions, asked for once per app start from Home.
 *
 * - BLUETOOTH_SCAN / BLUETOOTH_CONNECT (target 31+): without them the
 *   collector cannot scan for or connect to the sensor at all.
 * - POST_NOTIFICATIONS (target 33+): without it glucose alerts are silent.
 * - All files access (target 30+): export, import and settings files.
 */
public final class TargetSdkPermissions {

    private static final String TAG = "TargetSdkPermissions";
    private static final int REQUEST_CODE = 0x5D36;
    private static boolean askedThisRun;
    private static boolean filesAskedThisRun;

    private TargetSdkPermissions() {
    }

    public static void requestAtStartup(final Activity activity) {
        if (askedThisRun) {
            requestAllFilesAccess(activity);
            return;
        }
        askedThisRun = true;
        final List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(activity, needed, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(activity, needed, Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(activity, needed, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            UserError.Log.uel(TAG, "Requesting " + needed);
            ActivityCompat.requestPermissions(activity, needed.toArray(new String[0]), REQUEST_CODE);
        } else {
            requestAllFilesAccess(activity);
        }
    }

    private static void addIfMissing(Activity activity, List<String> needed, String permission) {
        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            needed.add(permission);
        }
    }

    // Asked after the runtime permissions so the two prompts do not overlap.
    private static void requestAllFilesAccess(final Activity activity) {
        if (filesAskedThisRun || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || StorageAccess.hasAllFilesAccess()) {
            return;
        }
        filesAskedThisRun = true;
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow all files access")
                    .setMessage("xDrip saves and imports backups, settings and logs in shared storage. "
                            + "Android needs \"All files access\" for that.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (Exception e) {
            UserError.Log.e(TAG, "All files access prompt failed: " + e);
        }
    }
}
