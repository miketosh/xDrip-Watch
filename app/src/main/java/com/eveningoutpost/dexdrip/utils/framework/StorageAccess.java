package com.eveningoutpost.dexdrip.utils.framework;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

/**
 * xDrip-Watch: shared-storage access for target SDK 30+.
 *
 * xDrip exports, imports and saves files with plain paths under shared
 * storage. From target 30 those need "All files access"
 * (MANAGE_EXTERNAL_STORAGE), and from 33 READ/WRITE_EXTERNAL_STORAGE are
 * always denied. Storage permission checks go through here: with All files
 * access granted they report GRANTED, otherwise the normal check applies.
 */
public final class StorageAccess {

    private StorageAccess() {
    }

    public static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
    }

    public static int checkSelfPermission(Context context, String permission) {
        if ((Manifest.permission.READ_EXTERNAL_STORAGE.equals(permission)
                || Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) && hasAllFilesAccess()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, permission);
    }
}
