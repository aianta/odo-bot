package ca.ualberta.odobot.debugger;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;

/**
 * What the Trajectory Debugger's compact timeline view shows of an entity, built from its {@code timeline_entities}
 * row, so a live entity, an imported one and a frozen one read the same.
 */
public final class EntitySummaries {

    private static final int MAX_LABEL = 160;

    private EntitySummaries() {
    }

    /**
     * @return {@code {kind, symbol, eventName, label, timestamp, location, hasScreenshot, hasCrop}}.
     */
    public static JsonObject of(JsonObject row) {
        JsonObject details = json(row.getValue("details"));
        String kind = row.getString("kind");
        return new JsonObject()
                .put("kind", kind)
                .put("symbol", row.getString("symbol"))
                .put("eventName", row.getString("event_name"))
                .put("label", truncate(label(kind, row, details)))
                .put("timestamp", row.getValue("timestamp"))
                .put("location", row.getString("location"))
                .put("hasScreenshot", row.getString("screenshot_path") != null)
                .put("hasCrop", row.getString("crop_path") != null);
    }

    private static String label(String kind, JsonObject row, JsonObject details) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case "ClickEvent" -> firstNonBlank(details.getString("text"), element(row));
            case "DataEntry" -> "“" + nullToEmpty(details.getString("enteredData")) + "” → " + element(row);
            case "CheckboxEvent" -> (Boolean.TRUE.equals(details.getValue("checked")) ? "checked " : "unchecked ") + element(row);
            case "SelectEvent" -> {
                JsonObject selected = json(details.getValue("selected"));
                yield "selected " + firstNonBlank(selected.getString("label"), String.valueOf(selected.getValue("value", ""))) + " in " + element(row);
            }
            case "Effect" -> {
                JsonObject actions = json(details.getValue("actions"));
                StringBuilder label = new StringBuilder(String.valueOf(details.getValue("domEffectCount", 0))).append(" DOM effect(s)");
                if (!actions.isEmpty()) {
                    label.append(": ");
                    actions.forEach(action -> label.append(action.getKey().toLowerCase()).append(' ').append(action.getValue()).append(", "));
                    label.setLength(label.length() - 2);
                }
                yield label.toString();
            }
            case "NetworkEvent" -> nullToEmpty(details.getString("method")) + " " + path(details.getString("url"));
            case "ApplicationLocationChange" -> "→ " + path(details.getString("to"));
            case "Observation" -> {
                String trigger = details.getString("trigger");
                Object step = details.getValue("stepNumber");
                yield (trigger == null ? "observation" : trigger) + (step == null ? "" : " (step " + step + ")")
                        + " @ " + path(row.getString("location"));
            }
            case "TaskAnswer" -> "answer: " + nullToEmpty(details.getString("answer"));
            default -> firstNonBlank(row.getString("event_name"), kind);
        };
    }

    private static String element(JsonObject row) {
        String tag = row.getString("tag");
        String id = row.getString("html_id");
        if (tag != null) {
            return tag + (id == null || id.isBlank() ? "" : "#" + id);
        }
        String xpath = row.getString("xpath");
        return xpath == null ? "" : xpath;
    }

    private static String path(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /**
     * @return a JSON column as an object: rows built live hold objects, rows read from a database hold JSON text.
     */
    static JsonObject json(Object value) {
        if (value instanceof JsonObject object) {
            return object;
        }
        if (value instanceof String text && text.startsWith("{")) {
            try {
                return new JsonObject(text);
            } catch (RuntimeException e) {
                return new JsonObject();
            }
        }
        return new JsonObject();
    }

    /**
     * @return a JSON column as an array, see {@link #json}.
     */
    static JsonArray array(Object value) {
        if (value instanceof JsonArray array) {
            return array;
        }
        if (value instanceof String text && text.startsWith("[")) {
            try {
                return new JsonArray(text);
            } catch (RuntimeException e) {
                return new JsonArray();
            }
        }
        return new JsonArray();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.strip() : nullToEmpty(b);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_LABEL ? flat : flat.substring(0, MAX_LABEL - 1) + "…";
    }
}
