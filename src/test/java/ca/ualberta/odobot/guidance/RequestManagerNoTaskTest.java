package ca.ualberta.odobot.guidance;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A client whose events no task consumes, e.g. a person recording a trajectory with the Trajectory Debugger: its
 * timeline is built as usual, and nothing is dispatched.
 */
class RequestManagerNoTaskTest {

    private Vertx vertx;
    private Context context;
    private OdoClient client;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = new OdoClient(UUID.randomUUID(), vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void eventsWithoutATaskBuildTheTimelineAndDispatchNothing() throws Exception {
        OnlineEventProcessor events = client.getEventConnectionManager().getEventProcessor();
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(v->{
            try{
                events.process(click(2_000, "page", null));
                events.process(domEffect(2_100, "add", "/html/body/div[2]", "Saved", "saved"));
                events.process(networkEvent(2_200, "POST", BASE_URI + "save"));
                done.complete(null);
            }catch (Throwable e){
                done.completeExceptionally(e);
            }
        });
        done.get(10, TimeUnit.SECONDS);

        assertEquals(3, events.timeline().size());
        assertNull(client.getRequestManager().getExecutionId());
    }
}
