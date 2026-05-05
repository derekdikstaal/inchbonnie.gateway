package com.dikstaal.gateway;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class SmtpClient {
    private SmtpClient() {
        // Utility class.
    }

    public static void sendGmail(
            String username,
            String appPassword,
            String toEmail,
            String subject,
            String message
    ) throws Exception {
        validate(username, appPassword, toEmail, message);

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) factory.createSocket(
                GatewayConstants.SMTP_HOST,
                GatewayConstants.SMTP_PORT
        );
        socket.setSoTimeout(GatewayConstants.SMTP_TIMEOUT_MS);

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            );

            expect(reader, 220);
            command(writer, reader, "EHLO android-gateway", 250);
            command(writer, reader, "AUTH LOGIN", 334);
            command(writer, reader, base64(username.trim()), 334);
            command(writer, reader, base64(appPassword.trim()), 235);
            command(writer, reader, "MAIL FROM:<" + username.trim() + ">", 250);
            command(writer, reader, "RCPT TO:<" + toEmail.trim() + ">", 250, 251);
            command(writer, reader, "DATA", 354);
			writer.write("From: \"IHS Gateway\" <"+username.trim()+">\r\n");
            writer.write(buildMessage(username.trim(), toEmail.trim(), subject, message));
            writer.write("\r\n.\r\n");
            writer.flush();
            expect(reader, 250);

            try {
                command(writer, reader, "QUIT", 221);
            } catch (Exception ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void validate(
            String username,
            String appPassword,
            String toEmail,
            String message
    ) throws Exception {
        if (isBlank(username)) {
            throw new Exception("Missing SMTP username");
        }
        if (isBlank(appPassword)) {
            throw new Exception("Missing SMTP app password");
        }
        if (isBlank(toEmail)) {
            throw new Exception("Missing email");
        }
        if (isBlank(message)) {
            throw new Exception("Missing message");
        }
    }

    private static String buildMessage(String from, String to, String subject, String body) {
        String safeBody = body.replace("\r\n.", "\r\n..").replace("\n.", "\n..");
        String safeSubject = isBlank(subject) ? GatewayConstants.DEFAULT_EMAIL_SUBJECT : subject.trim();

        return "From: <" + from + ">\r\n"
                + "To: <" + to + ">\r\n"
                + "Subject: " + safeSubject + "\r\n"
                + "Date: " + rfc2822Date() + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + safeBody + "\r\n";
    }

    private static String rfc2822Date() {
        SimpleDateFormat format = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss Z",
                Locale.US
        );
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static String base64(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );
    }

    private static void command(
            BufferedWriter writer,
            BufferedReader reader,
            String command,
            int... expectedCodes
    ) throws Exception {
        writer.write(command + "\r\n");
        writer.flush();
        expect(reader, expectedCodes);
    }

    private static void expect(BufferedReader reader, int... expectedCodes) throws Exception {
        String line = reader.readLine();
        if (line == null || line.length() < 3) {
            throw new Exception("SMTP server closed connection");
        }

        int code = parseCode(line);
        String lastLine = line;

        while (line.length() > 3 && line.charAt(3) == '-') {
            line = reader.readLine();
            if (line == null) {
                break;
            }
            lastLine = line;
            if (line.length() >= 3) {
                code = parseCode(line);
            }
        }

        for (int expected : expectedCodes) {
            if (code == expected) {
                return;
            }
        }

        throw new Exception("SMTP error: " + lastLine);
    }

    private static int parseCode(String line) throws Exception {
        try {
            return Integer.parseInt(line.substring(0, 3));
        } catch (Exception e) {
            throw new Exception("Invalid SMTP response: " + line);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
