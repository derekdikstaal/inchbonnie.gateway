package com.dikstaal.gateway;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GatewayHttpServer {
    private static final String TAG = GatewayConstants.TAG_HTTP_SERVER;

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public GatewayHttpServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() throws Exception {
        if (running.get()) {
            return;
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", GatewayConstants.DEFAULT_HTTP_PORT));
        running.set(true);

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "GatewayHttpServer-Accept");
        acceptThread.start();
    }

    public void stop() {
        running.set(false);

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }

        serverSocket = null;
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket socket = serverSocket.accept();
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(socket);
                    }
                });
            } catch (Exception e) {
                if (running.get()) {
                    Log.e(TAG, "Accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(GatewayConstants.HTTP_READ_TIMEOUT_MS);

            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            );

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                writeJson(output, 400, "{\"error\":\"bad_request\"}");
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(output, 400, "{\"error\":\"bad_request\"}");
                return;
            }

            String method = parts[0].toUpperCase(Locale.US);
            String path = stripQuery(parts[1]);
            int contentLength = readContentLength(reader);
            String body = readBody(reader, contentLength);

            if (GatewayConstants.HTTP_METHOD_OPTIONS.equals(method)) {
                writeJson(output, 200, "{\"success\":true}");
                return;
            }

            if (GatewayConstants.HTTP_METHOD_GET.equals(method)
                    && (GatewayConstants.PATH_ADMIN_ROOT.equals(path)
                    || GatewayConstants.PATH_ADMIN.equals(path))) {
                writeHtml(output, 200, buildAdminHtml());
                return;
            }

            if (GatewayConstants.HTTP_METHOD_GET.equals(method)
                    && GatewayConstants.PATH_CONFIG.equals(path)) {
                handleGetConfig(output);
                return;
            }

            if (GatewayConstants.HTTP_METHOD_POST.equals(method)
                    && GatewayConstants.PATH_CONFIG.equals(path)) {
                handleSetConfig(output, body);
                return;
            }

            if (GatewayConstants.HTTP_METHOD_GET.equals(method)
                    && GatewayConstants.PATH_STATUS.equals(path)) {
                writeJson(output, 200, "{\"status\":\"running\",\"port\":"
                        + GatewayConstants.DEFAULT_HTTP_PORT + "}");
                return;
            }

            if (GatewayConstants.HTTP_METHOD_GET.equals(method)
                    && GatewayConstants.PATH_LOGS.equals(path)) {
                handleGetLogs(output);
                return;
            }

            if (GatewayConstants.HTTP_METHOD_POST.equals(method)
                    && GatewayConstants.PATH_CLEAR_LOGS.equals(path)) {
                GatewayLog.clear(context);
                GatewayLog.i(context, TAG, "Logs cleared from web admin");
                writeJson(output, 200, "{\"success\":true}");
                return;
            }

            if (GatewayConstants.HTTP_METHOD_POST.equals(method)
                    && GatewayConstants.PATH_SEND_SMS.equals(path)) {
                handleSendSms(output, body);
                return;
            }

            if (GatewayConstants.HTTP_METHOD_POST.equals(method)
                    && GatewayConstants.PATH_SEND_EMAIL.equals(path)) {
                handleSendEmail(output, body);
                return;
            }

            writeJson(output, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            Log.e(TAG, "Client handling failed", e);
            try {
                writeJson(socket.getOutputStream(), 500, "{\"error\":\"internal_error\"}");
            } catch (Exception ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private int readContentLength(BufferedReader reader) throws Exception {
        int contentLength = 0;
        String header;

        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }

            String name = header.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = header.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return contentLength;
    }

    private String readBody(BufferedReader reader, int contentLength) throws Exception {
        if (contentLength <= 0) {
            return "";
        }

        char[] buffer = new char[contentLength];
        int offset = 0;

        while (offset < contentLength) {
            int read = reader.read(buffer, offset, contentLength - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }

        return new String(buffer, 0, offset);
    }

    private void handleGetConfig(OutputStream output) throws Exception {
        SharedPreferences prefs = getPrefs();
        String smtpAppKey = prefs.getString(GatewayConstants.PREF_SMTP_APP_KEY, "");

        String json = "{"
                + "\"port\":" + GatewayConstants.DEFAULT_HTTP_PORT + ","
                + "\"alert_phone\":\"" + jsonEscape(prefs.getString(GatewayConstants.PREF_ALERT_PHONE, "")) + "\"," 
                + "\"alert_email\":\"" + jsonEscape(prefs.getString(GatewayConstants.PREF_ALERT_EMAIL, "")) + "\"," 
                + "\"smtp_email\":\"" + jsonEscape(prefs.getString(GatewayConstants.PREF_SMTP_EMAIL, "")) + "\"," 
                + "\"smtp_configured\":" + (!isBlank(smtpAppKey))
                + "}";
        writeJson(output, 200, json);
    }

    private void handleSetConfig(OutputStream output, String body) throws Exception {
        String alertPhone = clean(SimpleJson.getString(body, "alert_phone"));
        String alertEmail = clean(SimpleJson.getString(body, "alert_email"));
        String smtpEmail = clean(SimpleJson.getString(body, "smtp_email"));
        String smtpAppKey = clean(SimpleJson.getString(body, "smtp_app_key"));

        SharedPreferences prefs = getPrefs();
        SharedPreferences.Editor editor = prefs.edit()
                .putString(GatewayConstants.PREF_ALERT_PHONE, alertPhone)
                .putString(GatewayConstants.PREF_ALERT_EMAIL, alertEmail)
                .putString(GatewayConstants.PREF_SMTP_EMAIL, smtpEmail);

        if (!isBlank(smtpAppKey)) {
            editor.putString(GatewayConstants.PREF_SMTP_APP_KEY, smtpAppKey);
        }

        editor.apply();
        GatewayLog.i(context, TAG, "Web config updated");
        writeJson(output, 200, "{\"success\":true}");
    }

    private void handleGetLogs(OutputStream output) throws Exception {
        String logs = GatewayLog.read(context);
        writeJson(output, 200, "{\"logs\":\"" + jsonEscape(logs) + "\"}");
    }

    private void handleSendSms(OutputStream output, String body) throws Exception {
        String number = clean(SimpleJson.getString(body, GatewayConstants.JSON_KEY_NUMBER));
        String message = SimpleJson.getString(body, GatewayConstants.JSON_KEY_MESSAGE);
        boolean useAlertRecipients = SimpleJson.getBoolean(
                body,
                GatewayConstants.JSON_KEY_USE_ALERT_RECIPIENTS,
                false
        );

        if (!hasSmsPermission()) {
            writeJson(output, 403, "{\"error\":\"sms_permission_not_granted\"}");
            return;
        }

        if (isBlank(message)) {
            writeJson(output, 400, "{\"error\":\"missing_message\"}");
            return;
        }

        String[] recipients;
        if (useAlertRecipients) {
            recipients = splitRecipients(getPrefs().getString(GatewayConstants.PREF_ALERT_PHONE, ""));
            if (recipients.length == 0) {
                writeJson(output, 400, "{\"error\":\"missing_alert_sms_recipients\"}");
                return;
            }
        } else {
            if (isBlank(number)) {
                writeJson(output, 400, "{\"error\":\"missing_number\"}");
                return;
            }
            recipients = new String[]{number};
        }

        try {
            SmsManager smsManager = getSmsManager();
            int sentCount = 0;

            for (String recipient : recipients) {
                recipient = clean(recipient);
                if (isBlank(recipient)) {
                    continue;
                }

                smsManager.sendTextMessage(recipient, null, message, null, null);
                sentCount++;
                GatewayLog.i(context, TAG, "SMS sent to " + recipient
                        + (useAlertRecipients ? " using alert recipients" : ""));
            }

            if (sentCount == 0) {
                writeJson(output, 400, "{\"error\":\"missing_sms_recipients\"}");
                return;
            }

            writeJson(output, 200, "{\"success\":true,\"sent\":" + sentCount + "}");
        } catch (Exception e) {
            GatewayLog.e(context, TAG, "SMS send failed", e);
            writeJson(output, 500, "{\"error\":\"sms_send_failed\"}");
        }
    }

    private void handleSendEmail(OutputStream output, String body) throws Exception {
        String email = clean(SimpleJson.getString(body, GatewayConstants.JSON_KEY_EMAIL));
        String message = SimpleJson.getString(body, GatewayConstants.JSON_KEY_MESSAGE);
        boolean useAlertRecipients = SimpleJson.getBoolean(
                body,
                GatewayConstants.JSON_KEY_USE_ALERT_RECIPIENTS,
                false
        );

        if (isBlank(message)) {
            writeJson(output, 400, "{\"error\":\"missing_message\"}");
            return;
        }

        SharedPreferences prefs = getPrefs();
        String username = prefs.getString(GatewayConstants.PREF_SMTP_EMAIL, null);
        String appPassword = prefs.getString(GatewayConstants.PREF_SMTP_APP_KEY, null);

        if (isBlank(username) || isBlank(appPassword)) {
            writeJson(output, 400, "{\"error\":\"smtp_not_configured\"}");
            return;
        }

        String[] recipients;
        if (useAlertRecipients) {
            recipients = splitRecipients(prefs.getString(GatewayConstants.PREF_ALERT_EMAIL, ""));
            if (recipients.length == 0) {
                writeJson(output, 400, "{\"error\":\"missing_alert_email_recipients\"}");
                return;
            }
        } else {
            if (isBlank(email)) {
                writeJson(output, 400, "{\"error\":\"missing_email\"}");
                return;
            }
            recipients = new String[]{email};
        }

        try {
            int sentCount = 0;
            for (String recipient : recipients) {
                recipient = clean(recipient);
                if (isBlank(recipient)) {
                    continue;
                }

                SmtpClient.sendGmail(username, appPassword, recipient, "HMI Notification", message);
                sentCount++;
                GatewayLog.i(context, TAG, "Email sent to " + recipient
                        + (useAlertRecipients ? " using alert recipients" : ""));
            }

            if (sentCount == 0) {
                writeJson(output, 400, "{\"error\":\"missing_email_recipients\"}");
                return;
            }

            writeJson(output, 200, "{\"success\":true,\"sent\":" + sentCount + "}");
        } catch (Exception e) {
            GatewayLog.e(context, TAG, "Email send failed", e);
            writeJson(output, 500, "{\"error\":\"email_send_failed\"}");
        }
    }

    private boolean hasSmsPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || context.checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SmsManager manager = context.getSystemService(SmsManager.class);
            if (manager != null) {
                return manager;
            }
        }
        return SmsManager.getDefault();
    }

    private String[] splitRecipients(String value) {
        if (isBlank(value)) {
            return new String[0];
        }
        return value.split("[\r\n,;]+");
    }

    private void writeJson(OutputStream output, int statusCode, String body) throws Exception {
        writeResponse(output, statusCode, "application/json; charset=utf-8", body);
    }

    private void writeHtml(OutputStream output, int statusCode, String body) throws Exception {
        writeResponse(output, statusCode, "text/html; charset=utf-8", body);
    }

    private void writeResponse(
            OutputStream output,
            int statusCode,
            String contentType,
            String body
    ) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + statusCode + " " + statusText(statusCode) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "\r\n";

        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    private String statusText(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 500:
                return "Internal Server Error";
            default:
                return "OK";
        }
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(GatewayConstants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String stripQuery(String path) {
        int queryIndex = path.indexOf('?');
        return queryIndex >= 0 ? path.substring(0, queryIndex) : path;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private String buildAdminHtml() {
        return "<!doctype html>"
                + "<html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>IHS Gateway Console</title>"
                + "<style>"
                + ":root{--primary:#2563eb;--bg:#f4f6f8;--card:#fff;--border:#e5e7eb;--success:#16a34a;--danger:#dc2626;--muted:#6b7280}"
                + "body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:var(--bg);color:#1f2937}"
                + "header{background:var(--primary);color:white;padding:16px;text-align:center;font-weight:800;font-size:18px}"
                + ".page{padding:16px;max-width:720px;margin:0 auto}.card{background:var(--card);border-radius:14px;padding:16px;margin-bottom:16px;border:1px solid var(--border);box-shadow:0 4px 14px rgba(0,0,0,.06)}"
                + "h2{margin:0 0 8px;font-size:17px}.note{color:var(--muted);font-size:13px;line-height:1.4}"
                + "label{display:block;margin:14px 0 6px;font-size:13px;font-weight:800;color:var(--muted)}"
                + "input,textarea{width:100%;box-sizing:border-box;padding:12px;border:1px solid var(--border);border-radius:10px;font-size:15px}"
                + "textarea{min-height:84px;resize:vertical}button{width:100%;padding:14px;margin-top:14px;border:0;border-radius:10px;font-weight:800;color:#fff;background:var(--primary);font-size:14px}"
                + ".success{background:var(--success)}.danger{background:var(--danger)}#toast{position:fixed;left:16px;right:16px;bottom:18px;background:#111827;color:#fff;padding:12px;border-radius:10px;font-size:13px;font-weight:800;text-align:center;opacity:0;transform:translateY(10px);transition:.2s}#toast.show{opacity:1;transform:translateY(0)}"
                + "</style></head><body>"
                + "<header>IHS Gateway Console</header><div class='page'>"
                //+ "<div class='card'><h2>Service</h2><p class='note'>HTTP gateway is running on fixed port 8080.</p><button onclick='loadConfig()'>Refresh Config</button></div>"
                + "<div class='card'><h2>Alert Settings</h2><p class='note'>Enter multiple recipients separated by commas or new lines.</p><label>Alert SMS Numbers</label><textarea id='alertPhone' placeholder='0211234567, 0217654321'></textarea><label>Alert Emails</label><textarea id='alertEmail' placeholder='alerts@example.com, backup@example.com'></textarea></div>"
                + "<div class='card'><h2>SMTP Settings</h2><p class='note'>Leave app key blank to keep the currently saved app key.</p><label>Gmail Address</label><input id='smtpEmail' placeholder='youraddress@gmail.com'><label>Gmail App Key</label><input id='smtpKey' type='password' placeholder='New app key or leave blank'><button class='success' onclick='saveConfig()'>Save Settings</button></div>"
                + "<div class='card'><h2>Test SMS</h2><p class='note'>Tick to send this message to all configured alert SMS recipients instead of the number below.</p><label><input id='smsUseAlerts' type='checkbox' style='width:auto;margin-right:8px'>Use alert SMS recipients</label><label>Mobile Number</label><input id='smsNumber'><label>Message</label><textarea id='smsMessage'>Test SMS from gateway</textarea><button onclick='sendSms()'>Send Test SMS</button></div>"
                + "<div class='card'><h2>Test Email</h2><p class='note'>Tick to send this message to all configured alert email recipients instead of the address below.</p><label><input id='emailUseAlerts' type='checkbox' style='width:auto;margin-right:8px'>Use alert email recipients</label><label>Recipient Email</label><input id='emailTo'><label>Message</label><textarea id='emailMessage'>Test email from gateway</textarea><button onclick='sendEmail()'>Send Test Email</button></div>"
                + "<div class='card'><h2>Logs</h2><p class='note'>Shows the most recent gateway log entries.</p><textarea id='logsBox' readonly style='min-height:260px;font-family:Consolas,monospace;font-size:12px'></textarea><button onclick='loadLogs()'>Refresh Logs</button><button class='danger' onclick='clearLogs()'>Clear Logs</button></div>"
                + "</div><div id='toast'></div>"
                + "<script>"
                + "function toast(m){var t=document.getElementById('toast');t.textContent=m;t.className='show';setTimeout(function(){t.className=''},2500)}"
                + "function v(id){return document.getElementById(id).value.trim()}function setv(id,x){document.getElementById(id).value=x||''}"
                + "function postJson(url,obj){return fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(obj)}).then(function(r){return r.text().then(function(t){if(!r.ok)throw new Error(t);return t})})}"
                + "function loadConfig(){fetch('/config').then(function(r){return r.json()}).then(function(c){setv('alertPhone',c.alert_phone);setv('alertEmail',c.alert_email);setv('smtpEmail',c.smtp_email);toast('Config loaded')}).catch(function(e){toast('Load failed: '+e.message)})}"
                + "function saveConfig(){postJson('/config',{alert_phone:v('alertPhone'),alert_email:v('alertEmail'),smtp_email:v('smtpEmail'),smtp_app_key:v('smtpKey')}).then(function(){setv('smtpKey','');toast('Settings saved');loadLogs()}).catch(function(e){toast('Save failed: '+e.message);loadLogs()})}"
                + "function checked(id){return !!document.getElementById(id).checked}function sendSms(){postJson('/send-sms',{number:v('smsNumber'),message:v('smsMessage'),use_alert_recipients:checked('smsUseAlerts')}).then(function(){toast('SMS sent');loadLogs()}).catch(function(e){toast('SMS failed: '+e.message);loadLogs()})}"
                + "function sendEmail(){postJson('/send-email',{email:v('emailTo'),message:v('emailMessage'),use_alert_recipients:checked('emailUseAlerts')}).then(function(){toast('Email sent');loadLogs()}).catch(function(e){toast('Email failed: '+e.message);loadLogs()})}"
                + "function loadLogs(){fetch('/logs').then(function(r){return r.json()}).then(function(c){setv('logsBox',c.logs||'')}).catch(function(e){toast('Log load failed: '+e.message)})}function clearLogs(){postJson('/logs/clear',{}).then(function(){toast('Logs cleared');loadLogs()}).catch(function(e){toast('Clear failed: '+e.message)})}document.addEventListener('DOMContentLoaded',function(){loadConfig();loadLogs()});"
                + "</script></body></html>";
    }
}
