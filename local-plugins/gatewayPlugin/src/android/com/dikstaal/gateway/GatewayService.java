package com.dikstaal.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class GatewayService extends Service {
    private static final String TAG = GatewayConstants.TAG_SERVICE;

    private static volatile boolean running = false;

    private GatewayHttpServer server;
    private Handler monitorHandler;
    private Runnable monitorRunnable;

    private boolean lastCharging = true;
    private boolean lastNetworkConnected = true;
    private boolean lastNetworkInternet = true;

    private long lastPowerAlertAt = 0;
    private long lastNetworkAlertAt = 0;
    private long lastInternetAlertAt = 0;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        monitorHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? GatewayConstants.ACTION_START : intent.getAction();

        if (GatewayConstants.ACTION_STOP.equals(action)) {
            stopServer();
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForeground(
                    GatewayConstants.NOTIFICATION_ID,
                    buildNotification()
            );
            startServer();
            startMonitoring();
            return START_STICKY;
        } catch (Exception e) {
            GatewayLog.e(this, TAG, "Failed to start gateway service", e);
            stopServer();
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private synchronized void startServer() throws Exception {
        if (server != null && running) {
            return;
        }

        stopServer();

        server = new GatewayHttpServer(getApplicationContext());
        server.start();
        running = true;
        GatewayLog.i(this, TAG, "HTTP gateway running on port " + GatewayConstants.DEFAULT_HTTP_PORT);
    }

    private synchronized void stopServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
            server = null;
        }

        running = false;
        GatewayLog.i(this, TAG, "HTTP gateway stopped");
    }

    private Notification buildNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = null;

        if (launchIntent != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                this,
                GatewayConstants.NOTIFICATION_CHANNEL_ID
        )
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Gateway running")
                .setContentText("HTTP server listening on port " + GatewayConstants.DEFAULT_HTTP_PORT)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                GatewayConstants.NOTIFICATION_CHANNEL_ID,
                GatewayConstants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(GatewayConstants.NOTIFICATION_CHANNEL_DESCRIPTION);

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void startMonitoring() {
        if (monitorHandler == null) {
            monitorHandler = new Handler(Looper.getMainLooper());
        }

        if (monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }

        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    checkPowerNetworkInternet();
                } catch (Exception e) {
                    GatewayLog.e(GatewayService.this, TAG, "Monitor check failed", e);
                }

                monitorHandler.postDelayed(this, GatewayConstants.MONITOR_INTERVAL_MS);
            }
        };

        monitorHandler.post(monitorRunnable);
    }

    private void stopMonitoring() {
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }
        monitorRunnable = null;
    }

    private void checkPowerNetworkInternet() {
        boolean charging = isDeviceCharging();
        boolean networkConnected = isNetworkConnected();
        boolean networkInternet = isNetworkInternetValidated();

        if (!charging && lastCharging) {
            sendGatewayAlert("Power failure at the station has been detected.");
        }

        if (charging && !lastCharging) {
            sendGatewayAlert("Power has been restored at the station.");
        }

        if (!networkConnected && lastNetworkConnected) {
            sendGatewayAlert("Ethernet/Wifi failure at the station has been detected.");
        }

        if (networkConnected && !lastNetworkConnected) {
            sendGatewayAlert("Ethernet/Wifi has been restored at the station.");
        }

        if (networkConnected && !networkInternet && lastNetworkInternet) {
            sendGatewayAlert("Internet failure at station has been detected.");
        }

        if (networkConnected && networkInternet && !lastNetworkInternet) {
            sendGatewayAlert("Internet has been restored at the station.");
        }

        lastCharging = charging;
        lastNetworkConnected = networkConnected;
        lastNetworkInternet = networkInternet;
    }

    private boolean isDeviceCharging() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);

        if (batteryStatus == null) {
            return true;
        }

        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB
                || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
    }

    private boolean isNetworkConnected() {
        NetworkCapabilities caps = getActiveNetworkCapabilities();
        return caps != null && ( 
			caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) );
    }

    private boolean isNetworkInternetValidated() {
        NetworkCapabilities caps = getActiveNetworkCapabilities();
        return caps != null
                && ( caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)|| caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private NetworkCapabilities getActiveNetworkCapabilities() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }

        Network network = manager.getActiveNetwork();
        if (network == null) {
            return null;
        }

        return manager.getNetworkCapabilities(network);
    }

    private long getLastAlertTime(String type) {
        if (GatewayConstants.ALERT_TYPE_POWER.equals(type)) {
            return lastPowerAlertAt;
        }
        if (GatewayConstants.ALERT_TYPE_NETWORK.equals(type)) {
            return lastNetworkAlertAt;
        }
        return lastInternetAlertAt;
    }

    private void setLastAlertTime(String type, long value) {
        if (GatewayConstants.ALERT_TYPE_POWER.equals(type)) {
            lastPowerAlertAt = value;
        } else if (GatewayConstants.ALERT_TYPE_NETWORK.equals(type)) {
            lastNetworkAlertAt = value;
        } else {
            lastInternetAlertAt = value;
        }
    }

    private void sendGatewayAlert(String message) {
        GatewayLog.w(this, TAG, message);

        SharedPreferences prefs = getPrefs();
        String alertPhones = prefs.getString(GatewayConstants.PREF_ALERT_PHONE, null);
        String alertEmails = prefs.getString(GatewayConstants.PREF_ALERT_EMAIL, null);

        String[] phones = splitRecipients(alertPhones);
        for (String phone : phones) {
            if (isBlank(phone)) {
                continue;
            }
            try {
                SmsManager sms = SmsManager.getDefault();
                sms.sendTextMessage(phone, null, message, null, null);
                GatewayLog.i(this, TAG, "Alert SMS sent to " + phone);
            } catch (Exception e) {
                GatewayLog.e(this, TAG, "Failed to send alert SMS to " + phone, e);
            }
        }

        String[] emails = splitRecipients(alertEmails);
        for (String email : emails) {
            if (!isBlank(email)) {
                sendAlertEmailAsync(email, "Station Network Notification", message);
            }
        }
    }

    private String[] splitRecipients(String value) {
        if (isBlank(value)) {
            return new String[0];
        }
        return value.split("[\r\n,;]+");
    }

    private void sendAlertEmailAsync(
            final String alertEmail,
            final String subject,
            final String message
    ) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = getPrefs();
                    String smtpUser = prefs.getString(GatewayConstants.PREF_SMTP_EMAIL, null);
                    String smtpKey = prefs.getString(GatewayConstants.PREF_SMTP_APP_KEY, null);

                    if (isBlank(smtpUser) || isBlank(smtpKey)) {
                        GatewayLog.e(GatewayService.this, TAG, "SMTP config missing", null);
                        return;
                    }

                    SmtpClient.sendGmail(smtpUser, smtpKey, alertEmail, subject, message);
                    GatewayLog.i(GatewayService.this, TAG, "Alert email sent to " + alertEmail);
                } catch (Exception e) {
                    GatewayLog.e(GatewayService.this, TAG, "Failed to send alert email to " + alertEmail, e);
                }
            }
        }, "GatewayAlertEmail").start();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(GatewayConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
