package com.dikstaal.gateway;

public final class SimpleJson {
    private SimpleJson() {}

    public static String getString(String json, String key) {
        if (json == null || key == null) return null;

        String quotedKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(quotedKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) return null;

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return null;
        valueStart++;

        StringBuilder value = new StringBuilder();
        boolean escaping = false;

        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaping) {
                switch (c) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    default: value.append(c); break;
                }
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                return value.toString();
            }

            value.append(c);
        }

        return null;
    }
}
