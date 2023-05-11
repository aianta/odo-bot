package ca.ualberta.odobot.logpreprocessor.executions;

import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import io.vertx.core.json.JsonObject;


import java.util.List;
import java.util.UUID;

/**
 * Metadata structure for the execution of a {@link PreprocessingPipeline}.
 *
 * Describes input, intermediary artifacts, outputs, etc.
 */
public interface PreprocessingPipelineExecution {

    /**
     * @return the list of elasticsearch indices used to produce the timelines for this execution
     */
    List<String> inputIndices();

    /**
     * @return The ids of the timelines produced in this execution
     */
    List<UUID> timelineIds();

    /**
     * @return The ids of the timeline entities used in this execution
     */
    List<String> timelineEntityIds();

    /**
     * @return The id of the activity labeling used in this execution.
     */
    UUID activityLabelingId();

    /**
     * @return retrieval information for the xlog produced during this execution.
     */
    ExternalArtifact xlog();

    /**
     * @return retrieval information for the process model visualizations produced during this execution.
     */
    List<ExternalArtifact> processModelVisualizations();

    /**
     * @return retrieval information for the process model produced during this execution.
     */
    ExternalArtifact processModel();

    /**
     * @return the id of the process model statistics object for this execution.
     */
    UUID processModelStatsId();

    /**
     * @return millisecond timestamp for when this execution was created.
     */
    long startTimestamp();

    /**
     * @return millisecond timestamp for when this execution terminated.
     */
    long endTimestamp();

    /**
     * @return status object for this execution, see {@link PreprocessingPipelineExecutionStatus}.
     */
    PreprocessingPipelineExecutionStatus status();

    /**
     * @return the id of the pipeline associated with this execution. See {@link PreprocessingPipeline}.
     */
    UUID pipelineId();

    /**
     * @return JSON representation of this execution.
     */
    JsonObject toJson();
}
