package ca.ualberta.odobot.common;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a task's artifacts go: {@code <folder>/<evalId><suffix>}. An evalId such as {@code 42|OdoBot|<uuid>} holds
 * '|', which Windows file names can't, so it becomes '-' in the file name. The folder is used as given.
 *
 * <p>The experiment's skip check, its token usage summary and the WebArena scheduler find the artifacts by these
 * names.</p>
 */
public record TaskArtifacts(Path folder, String evalId) {

    /**
     * The task's raw events, whose presence marks the task as run.
     */
    public static final String EVENTS = ".json";

    /**
     * The raw events, written as they arrive, until they are saved as {@link #EVENTS}.
     */
    public static final String EVENTS_PART = ".events.jsonl.part";

    public static final String TOKENS = "-tokens.json";

    public static final String TASK_QUERY_CONSTRUCTION_RESULT = "-task-query-construction-result.json";

    public TaskArtifacts {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(evalId, "evalId");
    }

    public static TaskArtifacts of(String folder, String evalId){
        return new TaskArtifacts(Path.of(folder), evalId);
    }

    /**
     * @return the evalId as it appears in file names, e.g. {@code 42-OdoBot-<uuid>}.
     */
    public static String fileStem(String evalId){
        return evalId.replace('|', '-');
    }

    /**
     * @param suffix appended as is, with its own separator, e.g. {@link #TOKENS} or {@link #EVENTS}.
     */
    public Path file(String suffix){
        return folder.resolve(fileStem(evalId) + suffix);
    }

    /**
     * @param source the connection manager whose message history it is, e.g. GuidanceConnectionManager.
     */
    public Path history(String source){
        return file("-" + source + "-history.json");
    }
}
