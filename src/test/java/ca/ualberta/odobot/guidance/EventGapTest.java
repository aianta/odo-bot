package ca.ualberta.odobot.guidance;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ca.ualberta.odobot.guidance.ChannelRoutingTest.message;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Events OdoX sent that never arrived are recorded in the raw events as EVENT_GAP custom events, where they are missing.
 */
class EventGapTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private final ChannelRoutingTest.RecordingSocket socket = new ChannelRoutingTest.RecordingSocket();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = ChannelRoutingTest.client(vertx);
        run(()->client.setSocket(socket));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static JsonObject event(int seq){
        return message("event", "EVENT")
                .put("seq", seq)
                .put("event", new JsonObject()
                        .put("eventType", "otherEvent")
                        .put("eventDetails", new JsonObject().put("name", "TEST").put("n", seq)));
    }

    private List<JsonObject> rawEvents() throws Exception {
        Path saved = dir.resolve("events.json");
        client.getEventConnectionManager().getEventProcessor().saveRawEvents(saved.toString());
        return new JsonArray(Buffer.buffer(Files.readAllBytes(saved))).stream().map(o->(JsonObject)o).toList();
    }

    private static boolean isGap(JsonObject event){
        return "EVENT_GAP".equals(event.getJsonObject("eventDetails").getString("name"));
    }

    @Test
    void eventsInSequenceRecordNoGap() throws Exception {
        for(int seq = 0; seq < 4; seq++){
            int n = seq;
            run(()->client.route(event(n)));
        }

        List<JsonObject> events = rawEvents();
        assertEquals(4, events.size());
        assertTrue(events.stream().noneMatch(EventGapTest::isGap));
    }

    @Test
    void aJumpInTheSequenceIsRecordedWhereTheEventsAreMissing() throws Exception {
        run(()->client.route(event(0)));
        run(()->client.route(event(1)));
        run(()->client.route(event(4)));

        List<JsonObject> events = rawEvents();
        assertEquals(4, events.size());
        JsonObject gap = events.get(2).getJsonObject("eventDetails");
        assertEquals("EVENT_GAP", gap.getString("name"));
        assertEquals(2, gap.getInteger("missing"));
        assertEquals(1, gap.getInteger("afterSeq"));
        assertEquals("sequence-gap", gap.getString("reason"));
        assertEquals(4, events.get(3).getJsonObject("eventDetails").getInteger("n"));
    }

    @Test
    void aNewTransmissionStartsTheSequenceOver() throws Exception {
        run(()->client.route(event(0)));
        run(()->client.route(event(1)));
        run(()->client.getEventConnectionManager().startTransmitting());
        run(()->client.route(event(0)));

        assertTrue(rawEvents().stream().noneMatch(EventGapTest::isGap));
    }

    @Test
    void messagesOdoXDroppedAreRecorded() throws Exception {
        run(()->client.route(event(0)));
        run(()->client.route(message("event", "EVENT_GAP")
                .put("dropped", 12)
                .put("reason", "outbox-overflow")
                .put("droppedTypes", new JsonObject().put("event/EVENT", 12))));

        List<JsonObject> events = rawEvents();
        JsonObject gap = events.get(1).getJsonObject("eventDetails");
        assertEquals("EVENT_GAP", gap.getString("name"));
        assertEquals(12, gap.getInteger("missing"));
        assertEquals(0, gap.getInteger("afterSeq"));
        assertEquals("outbox-overflow", gap.getString("reason"));
        assertEquals(12, gap.getJsonObject("details").getInteger("event/EVENT"));
    }

    @Test
    void eventsWithoutASequenceNumberAreNotChecked() throws Exception {
        JsonObject unnumbered = event(0);
        unnumbered.remove("seq");
        run(()->client.route(event(0)));
        run(()->client.route(unnumbered));
        run(()->client.route(event(1)));

        assertTrue(rawEvents().stream().noneMatch(EventGapTest::isGap));
    }

    private void run(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(v->{
            try{
                action.run();
                done.complete(null);
            }catch (Throwable t){
                done.completeExceptionally(t);
            }
        });
        done.get(5, TimeUnit.SECONDS);
    }
}
