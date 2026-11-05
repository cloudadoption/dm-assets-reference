package com.adobe.dmrefs.sites.core.util;

/**
 * Minimal streaming JSON writer. Avoids depending on a JSON library whose exported
 * version differs between AEM 6.5 and AEM as a Cloud Service.
 */
public final class JsonWriter {

    private final StringBuilder sb = new StringBuilder(4096);
    /** true when the next value in the current container needs a leading comma. */
    private final java.util.ArrayDeque<boolean[]> stack = new java.util.ArrayDeque<>();
    private boolean afterKey;

    public JsonWriter beginObject() {
        prefix();
        sb.append('{');
        stack.push(new boolean[] {false});
        return this;
    }

    public JsonWriter endObject() {
        stack.pop();
        sb.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        prefix();
        sb.append('[');
        stack.push(new boolean[] {false});
        return this;
    }

    public JsonWriter endArray() {
        stack.pop();
        sb.append(']');
        return this;
    }

    public JsonWriter name(String name) {
        prefix();
        string(name);
        sb.append(':');
        afterKey = true;
        return this;
    }

    public JsonWriter value(String v) {
        prefix();
        if (v == null) {
            sb.append("null");
        } else {
            string(v);
        }
        return this;
    }

    public JsonWriter value(long v) {
        prefix();
        sb.append(v);
        return this;
    }

    public JsonWriter value(boolean v) {
        prefix();
        sb.append(v);
        return this;
    }

    private void prefix() {
        if (afterKey) {
            afterKey = false;
            return;
        }
        boolean[] top = stack.peek();
        if (top != null) {
            if (top[0]) {
                sb.append(',');
            }
            top[0] = true;
        }
    }

    private void string(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
