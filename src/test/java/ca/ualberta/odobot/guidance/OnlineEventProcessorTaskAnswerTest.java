package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.TaskAnswer;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnlineEventProcessorTaskAnswerTest {

    @TempDir
    Path dir;

    @Test
    void answerIsOnTheTimelineAndInTheSavedRawEvents() throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        List<TimelineEntity> entities = new ArrayList<>();
        processor.setOnEntity(entities::add);

        processor.injectTaskAnswer("Lily Potter");

        assertEquals(1, entities.size());
        assertTrue(entities.get(0) instanceof TaskAnswer taskAnswer && taskAnswer.getAnswer().equals("Lily Potter"));

        Path saved = dir.resolve("events.json");
        processor.saveRawEvents(saved.toString());
        JsonArray events = new JsonArray(Buffer.buffer(Files.readAllBytes(saved)));
        assertEquals(1, events.size());
        JsonObject event = events.getJsonObject(0);
        assertEquals("customEvent", event.getString("eventType"));
        assertEquals("TASK_ANSWER", event.getJsonObject("eventDetails").getString("name"));
        assertEquals("Lily Potter", event.getJsonObject("eventDetails").getString("answer"));
        assertNotNull(event.getJsonObject("timestamps").getString("eventTimestamp"));
    }
}
