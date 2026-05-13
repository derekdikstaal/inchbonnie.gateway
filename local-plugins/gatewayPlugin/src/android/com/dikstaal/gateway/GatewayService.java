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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.core.app.NotificationCompat;

import android.content.pm.ServiceInfo;

public class GatewayService extends Service {
    private static final String TAG = GatewayConstants.TAG_SERVICE;

    private static volatile boolean running = false;

    private GatewayHttpServer server;
    private ServerSocket smtpServerSocket;
    private Thread smtpAcceptThread;
    private final AtomicBoolean smtpRunning = new AtomicBoolean(false);
    private final ExecutorService smtpWorkers = Executors.newCachedThreadPool();
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
            stopSmtpServer();
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
			
			if (Build.VERSION.SDK_INT >= 34) {
				startForeground(
					GatewayConstants.NOTIFICATION_ID,
					buildNotification(),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
				);
			} else {
				startForeground(GatewayConstants.NOTIFICATION_ID, buildNotification());
			}
			
            startServer();
            startSmtpServer();
            startMonitoring();
            return START_STICKY;
        } catch (Exception e) {
            GatewayLog.e(this, TAG, "Failed to start gateway service", e);
            stopServer();
            stopSmtpServer();
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }
	
	@Override
	public void onTimeout(int startId, int fgsType) {
		Log.e("GatewayService", "Foreground service timeout. type=" + fgsType);
		stopSelf(startId);
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


    private synchronized void startSmtpServer() {
        if (smtpRunning.get()) {
            return;
        }

        try {
            smtpServerSocket = new ServerSocket();
            smtpServerSocket.setReuseAddress(true);
            smtpServerSocket.bind(new InetSocketAddress("0.0.0.0", GatewayConstants.SMTP_RELAY_PORT));
            smtpRunning.set(true);

            smtpAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    smtpAcceptLoop();
                }
            }, "GatewaySmtpServer-Accept");
            smtpAcceptThread.start();

            GatewayLog.i(this, GatewayConstants.TAG_SMTP_SERVER,
                    "SMTP relay running on port " + GatewayConstants.SMTP_RELAY_PORT);
        } catch (Exception e) {
            smtpRunning.set(false);
            closeSmtpSocket();
            GatewayLog.e(this, GatewayConstants.TAG_SMTP_SERVER,
                    "Failed to start SMTP relay", e);
        }
    }

    private synchronized void stopSmtpServer() {
        smtpRunning.set(false);
        closeSmtpSocket();
        GatewayLog.i(this, GatewayConstants.TAG_SMTP_SERVER, "SMTP relay stopped");
    }

    private void closeSmtpSocket() {
        try {
            if (smtpServerSocket != null) {
                smtpServerSocket.close();
            }
        } catch (Exception ignored) {
        }
        smtpServerSocket = null;
    }

    private void smtpAcceptLoop() {
        while (smtpRunning.get()) {
            try {
                final Socket socket = smtpServerSocket.accept();
                smtpWorkers.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleSmtpClient(socket);
                    }
                });
            } catch (Exception e) {
                if (smtpRunning.get()) {
                    GatewayLog.e(this, GatewayConstants.TAG_SMTP_SERVER,
                            "SMTP accept failed", e);
                }
            }
        }
    }

    private void handleSmtpClient(Socket socket) {
        String remote = socket == null || socket.getInetAddress() == null
                ? "unknown"
                : socket.getInetAddress().getHostAddress();

        try {
            socket.setSoTimeout(GatewayConstants.SMTP_RELAY_READ_TIMEOUT_MS);

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));

            smtpWrite(writer, "220 IHS Gateway SMTP relay ready");

            boolean authenticated = false;
            boolean inData = false;
            String from = "";
            String to = "";
            StringBuilder data = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                if (inData) {
                    if (".".equals(line)) {
                        inData = false;
                        handleIncomingSmtpMessage(from, to, data.toString(), remote);
                        data.setLength(0);
                        smtpWrite(writer, "250 Message accepted");
                        continue;
                    }

                    if (data.length() + line.length() > GatewayConstants.SMTP_RELAY_MAX_MESSAGE_BYTES) {
                        inData = false;
                        data.setLength(0);
                        smtpWrite(writer, "552 Message too large");
                        continue;
                    }

                    data.append(line).append("\r\n");
                    continue;
                }

                String upper = line.toUpperCase(Locale.US);

                if (upper.startsWith("HELO") || upper.startsWith("EHLO")) {
                    smtpWrite(writer, "250-IHS Gateway");
                    smtpWrite(writer, "250-AUTH LOGIN PLAIN");
                    smtpWrite(writer, "250 OK");
                } else if (upper.startsWith("AUTH LOGIN")) {
                    authenticated = smtpAuthLogin(reader, writer);
                } else if (upper.startsWith("AUTH PLAIN")) {
                    authenticated = smtpAuthPlain(line, writer);
                } else if (!authenticated) {
                    smtpWrite(writer, "530 Authentication required");
                } else if (upper.startsWith("MAIL FROM:")) {
                    from = smtpExtractAddress(line.substring(10).trim());
                    smtpWrite(writer, "250 OK");
                } else if (upper.startsWith("RCPT TO:")) {
                    to = smtpExtractAddress(line.substring(8).trim());
                    smtpWrite(writer, "250 OK");
                } else if (upper.startsWith("DATA")) {
                    smtpWrite(writer, "354 End data with <CR><LF>.<CR><LF>");
                    inData = true;
                } else if (upper.startsWith("RSET")) {
                    from = "";
                    to = "";
                    data.setLength(0);
                    smtpWrite(writer, "250 OK");
                } else if (upper.startsWith("NOOP")) {
                    smtpWrite(writer, "250 OK");
                } else if (upper.startsWith("QUIT")) {
                    smtpWrite(writer, "221 Bye");
                    break;
                } else {
                    smtpWrite(writer, "502 Command not implemented");
                }
            }
        } catch (Exception e) {
            GatewayLog.e(this, GatewayConstants.TAG_SMTP_SERVER,
                    "SMTP client failed from " + remote, e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean smtpAuthLogin(BufferedReader reader, BufferedWriter writer) throws Exception {
        smtpWrite(writer, "334 VXNlcm5hbWU6");
        String username = smtpDecodeBase64(reader.readLine());

        smtpWrite(writer, "334 UGFzc3dvcmQ6");
        String password = smtpDecodeBase64(reader.readLine());

        if (smtpCredentialsValid(username, password)) {
            smtpWrite(writer, "235 Authentication successful");
            return true;
        }

        GatewayLog.w(this, GatewayConstants.TAG_SMTP_SERVER,
                "SMTP authentication failed for user " + username);
        smtpWrite(writer, "535 Authentication failed");
        return false;
    }

    private boolean smtpAuthPlain(String line, BufferedWriter writer) throws Exception {
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 3) {
            smtpWrite(writer, "334 ");
            return false;
        }

        String decoded = smtpDecodeBase64(parts[2]);
        String[] values = decoded.split(String.valueOf((char) 0), -1);
        String username = values.length >= 2 ? values[values.length - 2] : "";
        String password = values.length >= 1 ? values[values.length - 1] : "";

        if (smtpCredentialsValid(username, password)) {
            smtpWrite(writer, "235 Authentication successful");
            return true;
        }

        GatewayLog.w(this, GatewayConstants.TAG_SMTP_SERVER,
                "SMTP authentication failed for user " + username);
        smtpWrite(writer, "535 Authentication failed");
        return false;
    }

    private boolean smtpCredentialsValid(String username, String password) {
		return true;
        //return GatewayConstants.SMTP_RELAY_USERNAME.equals(username)
        //        && GatewayConstants.SMTP_RELAY_PASSWORD.equals(password);
    }

    private String smtpDecodeBase64(String value) {
        try {
            byte[] decoded = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
            return new String(decoded, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void handleIncomingSmtpMessage(String from, String to, String rawMessage, String remote) {
        String subject = smtpHeader(rawMessage, "Subject");
        String body = smtpBody(rawMessage);

        StringBuilder alert = new StringBuilder();
        //alert.append("SMTP gateway message received");
        //if (!isBlank(from)) {
        //    alert.append("\nFrom: ").append(from);
        //}
        //if (!isBlank(to)) {
        //    alert.append("\nTo: ").append(to);
        //}
        if (!isBlank(subject)) {
            alert.append(subject);
        }
        //alert.append("\nSource: ").append(remote);
        alert.append("\n");
        alert.append(body);

        GatewayLog.i(this, GatewayConstants.TAG_SMTP_SERVER, "SMTP message received from " + remote);
        sendGatewayAlert(alert.toString());
    }

    private String smtpHeader(String raw, String name) {
        String[] parts = raw.split("\\r?\\n\\r?\\n", 2);
        String headers = parts.length > 0 ? parts[0] : "";
        String prefix = name.toLowerCase(Locale.US) + ":";

        String[] lines = headers.split("\\r?\\n");
        StringBuilder value = new StringBuilder();
        boolean found = false;

        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith(prefix)) {
                found = true;
                value.append(line.substring(line.indexOf(':') + 1).trim());
            } else if (found && (line.startsWith(" ") || line.startsWith("\t"))) {
                value.append(' ').append(line.trim());
            } else if (found) {
                break;
            }
        }

        return value.toString().trim();
    }

    private String smtpBody(String raw) {
        String[] parts = raw.split("\\r?\\n\\r?\\n", 2);
        if (parts.length < 2) {
            return raw.trim();
        }
        return parts[1].trim();
    }

    private String smtpExtractAddress(String value) {
        value = value == null ? "" : value.trim();
        if (value.startsWith("<") && value.endsWith(">")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void smtpWrite(BufferedWriter writer, String line) throws Exception {
        writer.write(line + "\r\n");
        writer.flush();
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
                .setContentTitle("IHS Gateway running")
                .setContentText("HTTP server listening on port " + GatewayConstants.DEFAULT_HTTP_PORT + "\nSMPT server listening on port " + GatewayConstants.SMTP_RELAY_PORT)
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
        stopSmtpServer();
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
