package com.dikstaal.gateway;

/**
 * Central location for shared Gateway plugin constants.
 */
public final class GatewayConstants {
    public static final String PACKAGE_NAME = "com.dikstaal.gateway";

    public static final String TAG_BOOT_RECEIVER = "GatewayBootReceiver";
    public static final String TAG_HTTP_SERVER = "GatewayHttpServer";
    public static final String TAG_SERVICE = "GatewayService";

    public static final String PLUGIN_ACTION_START = "start";
    public static final String PLUGIN_ACTION_STOP = "stop";
    public static final String PLUGIN_ACTION_STATUS = "status";
    public static final String PLUGIN_ACTION_CHECK_SMS_PERMISSION = "checkSmsPermission";
    public static final String PLUGIN_ACTION_REQUEST_SMS_PERMISSION = "requestSmsPermission";
    public static final String PLUGIN_ACTION_CHECK_NOTIFICATION_PERMISSION = "checkNotificationPermission";
    public static final String PLUGIN_ACTION_REQUEST_NOTIFICATION_PERMISSION = "requestNotificationPermission";
    public static final String PLUGIN_ACTION_CHECK_OVERLAY_PERMISSION = "checkOverlayPermission";
    public static final String PLUGIN_ACTION_REQUEST_OVERLAY_PERMISSION = "requestOverlayPermission";
    public static final String PLUGIN_ACTION_CHECK_BATTERY_OPTIMIZATION = "checkBatteryOptimization";
    public static final String PLUGIN_ACTION_REQUEST_BATTERY_OPTIMIZATION = "requestBatteryOptimization";
	public static final String PLUGIN_ACTION_SET_CONFIG = "setConfig";

    public static final String ACTION_START = PACKAGE_NAME + ".START";
    public static final String ACTION_STOP = PACKAGE_NAME + ".STOP";


    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int HTTP_READ_TIMEOUT_MS = 30_000;

    public static final String PREFS_NAME = "gateway_config";
    public static final String PREF_ENABLED = "gateway_enabled";
    public static final String PREF_RESTORE_PENDING = "gateway_restore_pending";
    public static final String PREF_SMTP_EMAIL = "smtp_email";
    public static final String PREF_SMTP_APP_KEY = "smtp_app_key";
    public static final String PREF_ALERT_PHONE = "alert_phone";
    public static final String PREF_ALERT_EMAIL = "alert_email";

    public static final int REQ_SMS_PERMISSION = 8101;
    public static final int REQ_NOTIFICATION_PERMISSION = 8102;

    public static final String NOTIFICATION_CHANNEL_ID = "gateway_http_service";
    public static final int NOTIFICATION_ID = 44001;
    public static final String NOTIFICATION_CHANNEL_NAME = "Gateway service";
    public static final String NOTIFICATION_CHANNEL_DESCRIPTION =
            "Shows when the gateway HTTP service is running";

    public static final long MONITOR_INTERVAL_MS = 30_000;
    public static final long ALERT_COOLDOWN_MS = 10 * 60_000;

    public static final String ALERT_TYPE_POWER = "power";
    public static final String ALERT_TYPE_NETWORK = "network";
    public static final String ALERT_TYPE_INTERNET = "internet";

    public static final String SMTP_HOST = "smtp.gmail.com";
    public static final int SMTP_PORT = 465;
    public static final int SMTP_TIMEOUT_MS = 30_000;
    public static final String DEFAULT_EMAIL_SUBJECT = "Gateway message";

    public static final String HTTP_METHOD_GET = "GET";
    public static final String HTTP_METHOD_POST = "POST";
    public static final String HTTP_METHOD_OPTIONS = "OPTIONS";

    public static final String PATH_ADMIN_ROOT = "/";
    public static final String PATH_ADMIN = "/admin";
    public static final String PATH_CONFIG = "/config";
    public static final String PATH_STATUS = "/status";
    public static final String PATH_SEND_SMS = "/send-sms";
    public static final String PATH_SEND_EMAIL = "/send-email";
    public static final String PATH_LOGS = "/logs";
    public static final String PATH_CLEAR_LOGS = "/logs/clear";

    public static final String JSON_KEY_GRANTED = "granted";
    public static final String JSON_KEY_IGNORED = "ignored";
    public static final String JSON_KEY_OPENED = "opened";
    public static final String JSON_KEY_UNRESTRICTED = "unrestricted";
    public static final String JSON_KEY_RUNNING = "running";
    public static final String JSON_KEY_PORT = "port";
    public static final String JSON_KEY_SUCCESS = "success";
    public static final String JSON_KEY_CONFIGURED = "configured";
    public static final String JSON_KEY_ERROR = "error";
    public static final String JSON_KEY_EMAIL = "email";
    public static final String JSON_KEY_NUMBER = "number";
    public static final String JSON_KEY_MESSAGE = "message";

    public static final String LOG_FILE_NAME = "gateway.log";
    public static final int LOG_MAX_READ_BYTES = 64 * 1024;

    private GatewayConstants() {
        // Constants only.
    }
}
