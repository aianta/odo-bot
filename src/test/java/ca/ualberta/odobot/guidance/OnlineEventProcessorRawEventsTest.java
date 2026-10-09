package ca.ualberta.odobot.guidance;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OnlineEventProcessorRawEventsTest {

    @TempDir
    Path dir;

    /**
     * An event the processor records but does not map to a timeline entity.
     */
    private static JsonObject event(int i) {
        return new JsonObject()
                .put("eventType", "otherEvent")
                .put("eventDetails", new JsonObject().put("name", "TEST").put("i", i).put("text", "line\nbreak \"quoted\" é"));
    }

    private static JsonArray read(Path file) throws Exception {
        return new JsonArray(Buffer.buffer(Files.readAllBytes(file)));
    }

    @Test
    void streamsEventsToThePartialFileAndSavesThemInOrder() throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        processor.process(event(0)); // Before the file is opened: written when it is.
        Path part = dir.resolve("task.events.jsonl.part");
        processor.streamRawEventsTo(part);
        processor.process(event(1));
        processor.injectTaskAnswer("42");

        // One event per line, so the log is on disk rather than in memory.
        processor.saveRawEvents(dir.resolve("task.json").toString());
        assertEquals(3, Files.readAllLines(part).size());

        JsonArray events = read(dir.resolve("task.json"));
        assertEquals(3, events.size());
        assertEquals(event(0), events.getJsonObject(0));
        assertEquals(event(1), events.getJsonObject(1));
        assertEquals("42", events.getJsonObject(2).getJsonObject("eventDetails").getString("answer"));
    }

    @Test
    void clearingDeletesThePartialFile() throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        Path part = dir.resolve("task.events.jsonl.part");
        processor.streamRawEventsTo(part);
        processor.process(event(0));
        processor.saveRawEvents(dir.resolve("task.json").toString());

        processor.clearRawEvents();

        assertFalse(Files.exists(part));
        assertTrue(Files.exists(dir.resolve("task.json")));
        // Only the event file is left: no temporary file from saving.
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void savesFromMemoryWithoutAPartialFile() throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        processor.process(event(0));
        processor.process(event(1));

        processor.saveRawEvents(dir.resolve("task.json").toString());

        JsonArray events = read(dir.resolve("task.json"));
        assertEquals(2, events.size());
        assertEquals(event(1), events.getJsonObject(1));
    }

    @Test
    void savesAnEmptyArrayWithoutEvents() throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        processor.saveRawEvents(dir.resolve("task.json").toString());
        assertTrue(read(dir.resolve("task.json")).isEmpty());
    }

    @Test
    void aFailedSaveLeavesNoEventFile() throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        Path part = dir.resolve("task.events.jsonl.part");
        processor.streamRawEventsTo(part);
        processor.process(event(0));

        // The target's folder does not exist, so the save fails.
        Path target = dir.resolve("missing").resolve("task.json");
        assertThrows(RuntimeException.class, () -> processor.saveRawEvents(target.toString()));

        assertFalse(Files.exists(target));
        // The partial file is kept, so the events can be recovered.
        assertTrue(Files.exists(part));
    }
}
