package com.dikstaal.gateway;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = GatewayConstants.TAG_BOOT_RECEIVER;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(
                GatewayConstants.PREFS_NAME,
                Context.MODE_PRIVATE
        );

        boolean enabled = prefs.getBoolean(GatewayConstants.PREF_ENABLED, false);
        if (!enabled) {
            Log.i(TAG, "Gateway restart skipped; gateway is disabled");
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            prefs.edit()
                    .putBoolean(GatewayConstants.PREF_RESTORE_PENDING, true)
                    .apply();
            Log.w(TAG, "Gateway restart deferred; notification permission is not granted");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, GatewayService.class);
            serviceIntent.setAction(GatewayConstants.ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            prefs.edit()
                    .putBoolean(GatewayConstants.PREF_RESTORE_PENDING, false)
                    .apply();
            Log.i(TAG, "Gateway restart requested after boot");
        } catch (Exception e) {
            prefs.edit()
                    .putBoolean(GatewayConstants.PREF_RESTORE_PENDING, true)
                    .apply();
            Log.e(TAG, "Gateway restart failed after boot; restore pending", e);
        }
    }
}
