package ca.ualberta.odobot.trajectory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;

/**
 * OdoX events as they arrive on the event channel, for the trajectory tests.
 */
public final class TrajectoryFixtures {

    public static final String BASE_URI = "http://shop.test/admin/catalog/product/";

    private TrajectoryFixtures() {
    }

    public static String page(String marker) {
        return "<html><head></head><body><div id=\"grid\"><button id=\"save\">Save</button><input id=\"name\" placeholder=\"Name\">"
                + "<p>" + marker + "</p></div></body></html>";
    }

    public static String snapshot(String marker) {
        return new JsonObject().put("outerHTML", page(marker)).encode();
    }

    public static JsonObject boundingBox(int x, int y, int width, int height) {
        return new JsonObject().put("x", x).put("y", y).put("width", width).put("height", height)
                .put("top", y).put("bottom", y + height).put("left", x).put("right", x + width);
    }

    private static JsonObject timestamps(long millis) {
        return new JsonObject().put("eventTimestamp", Instant.ofEpochMilli(millis).toString());
    }

    /**
     * A click on the Save button, at (100, 50) to (180, 80) in the viewport.
     */
    public static JsonObject click(long millis, String marker, String screenshot) {
        JsonObject element = new JsonObject().put("xpath", "/html/body/div/button").put("localName", "button")
                .put("baseURI", BASE_URI).put("id", "save").put("outerHTML", "<button id=\"save\">Save</button>");
        JsonObject details = new JsonObject().put("name", "BTN_CLICK").put("type", "click")
                .put("xpath", "/html/body/div/button").put("element", element.encode())
                .put("domSnapshot", snapshot(marker)).put("boundingBox", boundingBox(100, 50, 80, 30));
        if (screenshot != null) {
            details.put("screenshot", screenshot);
        }
        return new JsonObject().put("eventType", "interactionEvent").put("eventDetails", details)
                .put("timestamps", timestamps(millis)).put("metadata", new JsonArray());
    }

    /**
     * A change of the Name input to {@code value}.
     */
    public static JsonObject input(long millis, String value, String marker) {
        JsonObject element = new JsonObject().put("xpath", "/html/body/div/input").put("localName", "input")
                .put("baseURI", BASE_URI).put("id", "name").put("outerHTML", "<input id=\"name\" placeholder=\"Name\">");
        JsonObject details = new JsonObject().put("name", "INPUT_CHANGE").put("type", "input")
                .put("xpath", "/html/body/div/input").put("element", element.encode())
                .put("domSnapshot", snapshot(marker)).put("boundingBox", boundingBox(300, 50, 120, 24))
                .put("checked", false);
        return new JsonObject().put("eventType", "interactionEvent").put("eventDetails", details)
                .put("timestamps", timestamps(millis))
                .put("metadata", new JsonArray().add(new JsonObject().put("name", "fieldValue").put("value", value)));
    }

    public static JsonObject domEffect(long millis, String action, String xpath, String text, String marker) {
        JsonObject node = new JsonObject().put("xpath", xpath).put("outerHTML", "<div>" + text + "</div>")
                .put("localName", "div").put("baseURI", BASE_URI).put("outerText", text);
        return new JsonObject().put("eventType", "customEvent").put("eventDetails", new JsonObject()
                        .put("name", "DOM_EFFECT").put("action", action).put("nodes", new JsonArray().add(node).encode())
                        .put("domSnapshot", snapshot(marker)))
                .put("timestamps", timestamps(millis));
    }

    public static JsonObject networkEvent(long millis, String method, String url) {
        JsonObject details = new JsonObject().put("name", "NETWORK_EVENT").put("method", method).put("url", url)
                .put("documentUrl", BASE_URI).put("type", "xmlhttprequest").put("requestId", "7")
                .put("timeStamp", String.valueOf(millis))
                .put("requestHeaders", new JsonObject().put("Cookie", "session=secret").put("Referer", BASE_URI).encode())
                .put("responseHeaders", new JsonObject().put("Set-Cookie", "session=new").put("Content-Type", "application/json").encode())
                .put("requestBody", new JsonObject().put("name", "Shirt").encode())
                .put("responseBody", new JsonObject().put("ok", true).encode());
        return new JsonObject().put("eventType", "customEvent").put("eventDetails", details)
                .put("timestamps", timestamps(millis)).put("sessionID", "s-1");
    }

    public static JsonObject startObservation(String screenshot) {
        return new JsonObject().put("eventType", "customEvent").put("eventDetails", new JsonObject()
                        .put("name", "OBSERVATION").put("trigger", "START_TRANSMISSION")
                        .put("userLocation", BASE_URI).put("screenshot", screenshot))
                .put("timestamps", timestamps(1_000));
    }

    /**
     * @return a base64 PNG of the given size, one colour.
     */
    public static String png(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
