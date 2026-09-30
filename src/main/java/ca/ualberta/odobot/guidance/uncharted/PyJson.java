package ca.ualberta.odobot.guidance.uncharted;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Serialises JSON the way Python's {@code json.dumps(value, ensure_ascii=False)} does: {@code ", "} and
 * {@code ": "} separators, non-ASCII characters left as is. The prompt text the model sees then matches the
 * Python agent's.
 */
final class PyJson {

    private PyJson() {
    }

    static String dumps(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof JsonObject object) {
            write(sb, object.getMap());
        } else if (value instanceof JsonArray array) {
            write(sb, array.getList());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(": ");
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                write(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof CharSequence text) {
            writeString(sb, text.toString());
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
