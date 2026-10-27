package ca.ualberta.odobot.debugger;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {

    private final Workspace workspace = new Workspace(() -> 1_000L);

    private static JsonObject summary(String symbol) {
        return new JsonObject().put("symbol", symbol);
    }

    private Workspace.Entity record(long liveId, String symbol) {
        return workspace.onLiveEntity("s1", liveId, summary(symbol), true);
    }

    private List<String> symbols(Workspace.Timeline timeline) {
        return workspace.timeline(timeline.id()).orElseThrow().entities().stream().map(e -> e.summary().getString("symbol")).toList();
    }

    @Test
    void theFirstRecordedEntityCreatesAnActiveUntitledTimeline() {
        assertTrue(workspace.timelines().isEmpty());
        record(0, "OBS");

        assertEquals(1, workspace.timelines().size());
        Workspace.Timeline timeline = workspace.timelines().get(0);
        assertEquals(Workspace.UNTITLED, timeline.title());
        assertEquals(timeline.id(), workspace.activeTimelineId());
        assertEquals(List.of("OBS"), symbols(timeline));
    }

    @Test
    void recordedEntitiesAppendToTheActiveTimelineAndChangesUpdateThemWhereverTheyAre() {
        Workspace.Timeline other = workspace.createTimeline("Other");
        record(0, "OBS");
        Workspace.Timeline recording = workspace.timelines().get(1);
        record(1, "DE");
        workspace.onLiveEntity("s1", 1, summary("DE*"), false);

        workspace.setActive(other.id());
        record(2, "CE");

        assertEquals(List.of("OBS", "DE*"), symbols(recording));
        assertEquals(List.of("CE"), symbols(other), "a timeline that already had events would get them appended at its end");
        assertNull(workspace.onLiveEntity("s1", 99, summary("E"), false), "a change to an entity that is gone is dropped");
    }

    @Test
    void aNewBlankTimelineIsNotActiveAndIsTitledUntitled() {
        record(0, "OBS");
        String active = workspace.activeTimelineId();
        Workspace.Timeline blank = workspace.createTimeline("  ");
        assertEquals(Workspace.UNTITLED, blank.title());
        assertEquals(active, workspace.activeTimelineId());
        assertTrue(blank.entities().isEmpty());
    }

    @Test
    void cuttingTheActiveTimelineMakesItsSecondHalfActiveAndKeepsNotesWithTheirEntities() {
        record(0, "OBS");
        record(1, "CE");
        Workspace.Entity entry = record(2, "DE");
        record(3, "E");
        Workspace.Timeline timeline = workspace.timelines().get(0);
        workspace.rename(timeline.id(), "Login");
        workspace.setEntityNotes(entry.id(), "typed the name");
        String cutAt = timeline.entities().get(1).id();

        Workspace.Cut cut = workspace.cut(timeline.id(), cutAt);

        assertEquals(List.of("OBS", "CE"), symbols(cut.first()));
        assertEquals(List.of("DE", "E"), symbols(cut.second()));
        assertEquals("Login (2)", cut.second().title());
        assertEquals(List.of(timeline.id(), cut.second().id()), workspace.timelines().stream().map(Workspace.Timeline::id).toList(),
                "the second half goes right below the first");
        assertEquals(cut.second().id(), workspace.activeTimelineId());
        assertEquals("typed the name", workspace.entity(entry.id()).orElseThrow().notes());

        record(4, "NET");
        assertEquals(List.of("DE", "E", "NET"), symbols(cut.second()), "recording continues after the latest entities");
    }

    @Test
    void cuttingAnInactiveTimelineLeavesTheActiveOneAlone() {
        record(0, "OBS");
        record(1, "CE");
        Workspace.Timeline first = workspace.timelines().get(0);
        Workspace.Timeline other = workspace.createTimeline(null);
        workspace.setActive(other.id());

        Workspace.Cut cut = workspace.cut(first.id(), first.entities().get(0).id());
        assertNotNull(cut.second());
        assertEquals(other.id(), workspace.activeTimelineId());
    }

    @Test
    void cuttingAtTheLastEntityChangesNothing() {
        record(0, "OBS");
        Workspace.Timeline timeline = workspace.timelines().get(0);
        long version = workspace.version();
        Workspace.Cut cut = workspace.cut(timeline.id(), timeline.entities().get(0).id());
        assertNull(cut.second());
        assertEquals(1, workspace.timelines().size());
        assertEquals(version, workspace.version());
    }

    @Test
    void deletingTheActiveTimelineLeavesNoneActiveUntilTheNextRecordedEntityCreatesOne() {
        record(0, "OBS");
        Workspace.Timeline first = workspace.timelines().get(0);
        List<Workspace.Entity> removed = workspace.delete(first.id());
        assertEquals(1, removed.size());
        assertNull(workspace.activeTimelineId());

        record(1, "CE");
        assertEquals(1, workspace.timelines().size());
        assertNotEquals(first.id(), workspace.activeTimelineId());
    }

    @Test
    void everyChangeBumpsTheVersionAndTheWorkspaceRoundTripsThroughJson() {
        long v0 = workspace.version();
        record(0, "OBS");
        Workspace.Timeline timeline = workspace.timelines().get(0);
        workspace.setNotes(timeline.id(), "first try");
        assertTrue(workspace.version() > v0);

        Workspace.Entity entity = workspace.timelines().get(0).entities().get(0);
        workspace.frozen(entity, summary("OBS!"));
        Workspace copy = Workspace.fromJson(workspace.toJson());
        assertEquals(workspace.toJson(), copy.toJson());
        assertEquals("first try", copy.timelines().get(0).notes());
        assertFalse(copy.timelines().get(0).entities().get(0).isLive());
    }

    @Test
    void liveEntitiesAreDroppedWhenTheirSessionIsGone() {
        record(0, "OBS");
        record(1, "CE");
        workspace.frozen(workspace.timelines().get(0).entities().get(0), null);
        assertEquals(1, workspace.dropLive());
        assertEquals(List.of("OBS"), symbols(workspace.timelines().get(0)));
    }

    @Test
    void importedTimelinesGetNewIdsAndAreNotActive() {
        record(0, "OBS");
        String active = workspace.activeTimelineId();
        Workspace.Timeline imported = workspace.addImported("task 2", "notes", new JsonObject().put("eval_id", "2|x"),
                List.of(new JsonObject().put("summary", summary("CE")).put("notes", "a note")));
        assertEquals(active, workspace.activeTimelineId());
        assertEquals("a note", imported.entities().get(0).notes());
        assertFalse(imported.entities().get(0).isLive());
        assertEquals("2|x", imported.source().getString("eval_id"));
    }
}
