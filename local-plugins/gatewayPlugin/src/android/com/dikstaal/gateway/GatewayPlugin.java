package com.dikstaal.gateway;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

public class GatewayPlugin extends CordovaPlugin {
    private CallbackContext pendingSmsPermissionCallback;
    private CallbackContext pendingNotificationPermissionCallback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        try {
            //if (GatewayConstants.PLUGIN_ACTION_PING.equals(action)) {
            //    callbackContext.success("pong");
            //    return true;
            //}
        Log.i(GatewayConstants.TAG_SERVICE, "Action: " + action);
            if (GatewayConstants.PLUGIN_ACTION_START.equals(action)) {
                if (!hasNotificationPermission()) {
                    callbackContext.error(
                            "Notification permission is required before starting the foreground gateway service"
                    );
                    return true;
                }

                startGateway();

                JSONObject result = new JSONObject();
                result.put(GatewayConstants.JSON_KEY_RUNNING, true);
                callbackContext.success(result);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_STOP.equals(action)) {
                stopGateway();

                JSONObject result = new JSONObject();
                result.put(GatewayConstants.JSON_KEY_RUNNING, false);
                callbackContext.success(result);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_STATUS.equals(action)) {
                JSONObject result = new JSONObject();
                result.put(GatewayConstants.JSON_KEY_RUNNING, GatewayService.isRunning());
                callbackContext.success(result);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_CHECK_SMS_PERMISSION.equals(action)) {
                sendPermissionResult(callbackContext, hasSmsPermission());
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_REQUEST_SMS_PERMISSION.equals(action)) {
                requestSmsPermission(callbackContext);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_CHECK_NOTIFICATION_PERMISSION.equals(action)) {
                sendPermissionResult(callbackContext, hasNotificationPermission());
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_REQUEST_NOTIFICATION_PERMISSION.equals(action)) {
                requestNotificationPermission(callbackContext);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_CHECK_OVERLAY_PERMISSION.equals(action)) {
                sendPermissionResult(callbackContext, hasOverlayPermission());
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_REQUEST_OVERLAY_PERMISSION.equals(action)) {
                requestOverlayPermission(callbackContext);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_CHECK_BATTERY_OPTIMIZATION.equals(action)) {
                sendBatteryOptimizationResult(callbackContext, isIgnoringBatteryOptimizations(), false);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_REQUEST_BATTERY_OPTIMIZATION.equals(action)) {
                requestBatteryOptimization(callbackContext);
                return true;
            }

            if (GatewayConstants.PLUGIN_ACTION_SET_CONFIG.equals(action)) {
                setConfig(
                        args.optString(0, ""),
                        args.optString(1, ""),
                        args.optString(2, ""),
                        args.optString(3, "")
                );

                JSONObject result = new JSONObject();
                result.put("status", "saved");
                callbackContext.success(result);
                return true;
            }

            return false;
        } catch (Exception e) {
            callbackContext.error(e.getMessage() == null ? e.toString() : e.getMessage());
            return true;
        }
    }

    private void startGateway() {
        getPrefs().edit()
                .putBoolean(GatewayConstants.PREF_ENABLED, true)
                .putBoolean(GatewayConstants.PREF_RESTORE_PENDING, false)
                .apply();

        Context context = cordova.getActivity().getApplicationContext();
        Intent intent = new Intent(context, GatewayService.class);
        intent.setAction(GatewayConstants.ACTION_START);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void stopGateway() {
        getPrefs().edit()
                .putBoolean(GatewayConstants.PREF_ENABLED, false)
                .putBoolean(GatewayConstants.PREF_RESTORE_PENDING, false)
                .apply();

        Context context = cordova.getActivity().getApplicationContext();
        Intent intent = new Intent(context, GatewayService.class);
        intent.setAction(GatewayConstants.ACTION_STOP);
        context.startService(intent);
    }

    private boolean hasSmsPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || cordova.hasPermission(Manifest.permission.SEND_SMS);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return cordova.getActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(cordova.getActivity().getApplicationContext());
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        Context context = cordova.getActivity().getApplicationContext();
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private void requestSmsPermission(CallbackContext callbackContext) throws Exception {
        if (hasSmsPermission()) {
            sendPermissionResult(callbackContext, true);
            return;
        }

        if (pendingSmsPermissionCallback != null) {
            callbackContext.error("SMS permission request already in progress");
            return;
        }

        pendingSmsPermissionCallback = callbackContext;
        cordova.requestPermission(
                this,
                GatewayConstants.REQ_SMS_PERMISSION,
                Manifest.permission.SEND_SMS
        );
    }

    private void requestNotificationPermission(CallbackContext callbackContext) throws Exception {
        if (hasNotificationPermission() || Build.VERSION.SDK_INT < 33) {
            sendPermissionResult(callbackContext, true);
            return;
        }

        if (pendingNotificationPermissionCallback != null) {
            callbackContext.error("Notification permission request already in progress");
            return;
        }

        pendingNotificationPermissionCallback = callbackContext;
        cordova.requestPermission(
                this,
                GatewayConstants.REQ_NOTIFICATION_PERMISSION,
                Manifest.permission.POST_NOTIFICATIONS
        );
    }

    private void requestOverlayPermission(CallbackContext callbackContext) throws Exception {
        if (hasOverlayPermission()) {
            JSONObject result = permissionJson(true);
            result.put(GatewayConstants.JSON_KEY_OPENED, false);
            callbackContext.success(result);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Context context = cordova.getActivity().getApplicationContext();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
            );
            cordova.getActivity().startActivity(intent);
        }

        JSONObject result = permissionJson(false);
        result.put(GatewayConstants.JSON_KEY_OPENED, true);
        callbackContext.success(result);
    }

    private void requestBatteryOptimization(CallbackContext callbackContext) throws Exception {
        boolean unrestricted = isIgnoringBatteryOptimizations();
        if (unrestricted) {
            sendBatteryOptimizationResult(callbackContext, true, false);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Context context = cordova.getActivity().getApplicationContext();
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            cordova.getActivity().startActivity(intent);
        }

        sendBatteryOptimizationResult(callbackContext, false, true);
    }

    private void setConfig(
            String alertPhone,
            String alertEmail,
            String smtpEmail,
            String smtpAppKey
    ) {
        getPrefs().edit()
                .putString(GatewayConstants.PREF_ALERT_PHONE, clean(alertPhone))
                .putString(GatewayConstants.PREF_ALERT_EMAIL, clean(alertEmail))
                .putString(GatewayConstants.PREF_SMTP_EMAIL, clean(smtpEmail))
                .putString(GatewayConstants.PREF_SMTP_APP_KEY, clean(smtpAppKey))
                .apply();
    }

    private SharedPreferences getPrefs() {
        return cordova.getActivity().getApplicationContext()
                .getSharedPreferences(GatewayConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void sendPermissionResult(CallbackContext callbackContext, boolean granted)
            throws Exception {
        callbackContext.success(permissionJson(granted));
    }

    private JSONObject permissionJson(boolean granted) throws Exception {
        JSONObject result = new JSONObject();
        result.put(GatewayConstants.JSON_KEY_GRANTED, granted);
        return result;
    }

    private void sendBatteryOptimizationResult(
            CallbackContext callbackContext,
            boolean unrestricted,
            boolean opened
    ) throws Exception {
        JSONObject result = new JSONObject();
        result.put(GatewayConstants.JSON_KEY_UNRESTRICTED, unrestricted);
        result.put(GatewayConstants.JSON_KEY_IGNORED, unrestricted);
        result.put(GatewayConstants.JSON_KEY_OPENED, opened);
        callbackContext.success(result);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == GatewayConstants.REQ_SMS_PERMISSION) {
            completePermissionRequest(pendingSmsPermissionCallback, grantResults);
            pendingSmsPermissionCallback = null;
            return;
        }

        if (requestCode == GatewayConstants.REQ_NOTIFICATION_PERMISSION) {
            completePermissionRequest(pendingNotificationPermissionCallback, grantResults);
            pendingNotificationPermissionCallback = null;
        }
    }

    private void completePermissionRequest(CallbackContext callback, int[] grantResults) {
        if (callback == null) {
            return;
        }

        try {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            sendPermissionResult(callback, granted);
        } catch (Exception e) {
            callback.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
