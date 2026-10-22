package ca.ualberta.odobot.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TaskArtifactsTest {

    private static final String EVAL_ID = "42|OdoBot|9b7c3e9a-1f0e-4a43-9a40-6c1d2f6f0b11";
    private static final String STEM = "42-OdoBot-9b7c3e9a-1f0e-4a43-9a40-6c1d2f6f0b11";

    private final TaskArtifacts artifacts = TaskArtifacts.of("experiments/exp-1", EVAL_ID);

    private String name(Path path){
        return path.getFileName().toString();
    }

    @Test
    void pipesInTheEvalIdBecomeHyphens() {
        assertEquals(STEM, TaskArtifacts.fileStem(EVAL_ID));
        assertEquals("no-pipes", TaskArtifacts.fileStem("no-pipes"));
    }

    @Test
    void theSuffixIsAppendedAsIs() {
        assertEquals(STEM + "-anything.txt", name(artifacts.file("-anything.txt")));
        assertEquals(STEM + ".txt", name(artifacts.file(".txt")));
    }

    /**
     * The experiment's skip check, its token usage summary and the WebArena scheduler find the artifacts by these
     * names, so they must not change.
     */
    @Test
    void artifactNamesAreUnchanged() {
        assertEquals(STEM + ".json", name(artifacts.file(TaskArtifacts.EVENTS)));
        assertEquals(STEM + ".events.jsonl.part", name(artifacts.file(TaskArtifacts.EVENTS_PART)));
        assertEquals(STEM + "-tokens.json", name(artifacts.file(TaskArtifacts.TOKENS)));
        assertEquals(STEM + "-task-query-construction-result.json", name(artifacts.file(TaskArtifacts.TASK_QUERY_CONSTRUCTION_RESULT)));
        assertEquals(STEM + "-GuidanceConnectionManager-history.json", name(artifacts.history("GuidanceConnectionManager")));
        assertEquals(Path.of("experiments/exp-1"), artifacts.file(TaskArtifacts.EVENTS).getParent());
    }

    @Test
    void anEvalIdIsRequired() {
        assertThrows(NullPointerException.class, ()->TaskArtifacts.of("experiments/exp-1", null));
    }
}
