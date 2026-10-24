package com.eveningoutpost.dexdrip.utilitymodels;

import android.Manifest;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.models.UserError.Log;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
import static com.eveningoutpost.dexdrip.utilitymodels.Notifications.ongoingNotificationId;

/**
 * Created by Emma Black on 12/25/14.
 */
public class ForegroundServiceStarter {

    private static final String TAG = "FOREGROUND";

    final private Service mService;
    final private Context mContext;
    //final private Handler mHandler;


    public ForegroundServiceStarter(Context context, Service service) {
        mContext = context;
        mService = service;
        //mHandler = new Handler(Looper.getMainLooper());

    }


    public void start() {
        if (mService == null) {
            Log.e(TAG, "SERVICE IS NULL - CANNOT START!");
            return;
        }
        Log.d(TAG, "should be moving to foreground");
        // mHandler.post(new Runnable() {
        //     @Override
        //     public void run() {
        // TODO use constants
        final long end = System.currentTimeMillis() + (60000 * 5);
        final long start = end - (60000 * 60 * 3) - (60000 * 10);
        foregroundStatus();
        Log.d(TAG, "CALLING START FOREGROUND: " + mService.getClass().getSimpleName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                /*
                  When is a foreground service not a foreground service?
                  When it's started from the background of course!
                  On android 11, even though the user explicitly grants us permission to use
                  background location, we still have to request to use it on a foreground
                  service, but only when it isn't re-started with the app open.
                  Even then the restrictions seem to be applied inconsistently!
                 */
            try {
                mService.startForeground(ongoingNotificationId, new Notifications().createOngoingNotification(new BgGraphBuilder(mContext, start, end), mContext), foregroundTypes(true));
            } catch (SecurityException e) {
                // xDrip-Watch: a type's prerequisite was missing (target 34+); retry without location.
                UserError.Log.e(TAG, "Foreground start refused for " + mService.getClass().getSimpleName() + ", retrying without location: " + e);
                mService.startForeground(ongoingNotificationId, new Notifications().createOngoingNotification(new BgGraphBuilder(mContext, start, end), mContext), foregroundTypes(false));
            } catch (IllegalArgumentException e) {
                UserError.Log.e(TAG, "Got exception trying to use Android 10+ service starting for " + mService.getClass().getSimpleName() + " " + e);
                mService.startForeground(ongoingNotificationId, new Notifications().createOngoingNotification(new BgGraphBuilder(mContext, start, end), mContext));
            }
        } else {
            mService.startForeground(ongoingNotificationId, new Notifications().createOngoingNotification(new BgGraphBuilder(mContext, start, end), mContext));
        }

        //     }
        // });

    }

    /**
     * xDrip-Watch: from target 34 Android checks the prerequisites of every
     * requested type, and FOREGROUND_SERVICE_TYPE_MANIFEST requests them all.
     * Request this service's declared types, without location unless a
     * location permission is granted (connectedDevice is satisfied by
     * CHANGE_NETWORK_STATE in the manifest).
     */
    private int foregroundTypes(final boolean allowLocation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return FOREGROUND_SERVICE_TYPE_MANIFEST;
        }
        int types;
        try {
            types = mContext.getPackageManager()
                    .getServiceInfo(new ComponentName(mContext, mService.getClass()), 0)
                    .getForegroundServiceType();
        } catch (Exception e) {
            UserError.Log.e(TAG, "Cannot read foreground service type for " + mService.getClass().getSimpleName() + ": " + e);
            return FOREGROUND_SERVICE_TYPE_MANIFEST;
        }
        final boolean locationGranted =
                ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!allowLocation || !locationGranted) {
            final int withoutLocation = types & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (withoutLocation != 0) {
                types = withoutLocation;
            }
        }
        return types == 0 ? FOREGROUND_SERVICE_TYPE_MANIFEST : types;
    }

    public void stop() {
        Log.d(TAG, "should be moving out of foreground");
        mService.stopForeground(true);
    }

    protected void foregroundStatus() {
        Inevitable.task("foreground-status", 2000, () -> UserError.Log.d("XFOREGROUND", mService.getClass().getSimpleName() + (JoH.isServiceRunningInForeground(mService.getClass()) ? " is running in foreground" : " is not running in foreground")));
    }

}
