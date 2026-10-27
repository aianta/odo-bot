package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.trajectory.TrajectoryWriter;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;

/**
 * A recorded charted timeline for the debugger tests: a start observation, a click, two input changes into one data
 * entry, a DOM effect and a POST.
 */
final class DebuggerFixtures {

    private DebuggerFixtures() {
    }

    static OnlineEventProcessor recording() {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        processor.setTrajectoryCapture(true);
        processor.process(startObservation(png(400, 300, Color.WHITE), snapshot("start")));
        processor.process(click(2_000, "clicked", png(400, 300, Color.BLUE)));
        processor.process(input(3_000, "S", "typing"));
        processor.process(input(3_100, "Shirt", "typed"));
        processor.process(domEffect(3_200, "add", "/html/body/div[2]", "Saved", "saved"));
        processor.process(networkEvent(3_300, "POST", BASE_URI + "save"));
        return processor;
    }

    /**
     * Record the processor's timeline into the workspace, as a live session would: each entity arrives live and is
     * frozen into its folder.
     *
     * @return the timeline.
     */
    static Workspace.Timeline record(Workspace workspace, OnlineEventProcessor processor, Path entitiesDir) throws Exception {
        List<TimelineEntity> timeline = processor.timeline();
        for (int i = 0; i < timeline.size(); i++) {
            TrajectoryWriter.EntityRow row = TrajectoryWriter.chartedRow(timeline.get(i), 0, processor,
                    entitiesDir.resolve("staging-" + i), "live", "s1");
            Workspace.Entity entity = workspace.onLiveEntity("s1", i, EntitySummaries.of(row.row()), true);
            EntityFolder.write(entitiesDir.resolve("staging-" + i), row);
            EntityFolder.move(entitiesDir.resolve("staging-" + i), entitiesDir.resolve(entity.id()));
            workspace.frozen(entity, null);
        }
        return workspace.timelines().get(workspace.timelines().size() - 1);
    }
}
