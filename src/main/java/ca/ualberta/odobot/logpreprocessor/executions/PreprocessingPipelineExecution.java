package ca.ualberta.odobot.logpreprocessor.executions;

import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import io.vertx.core.json.JsonObject;


import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Metadata structure for the execution of a {@link PreprocessingPipeline}.
 *
 * Describes input, intermediary artifacts, outputs, etc.
 */
public interface PreprocessingPipelineExecution {

    /**
     * @return the execution's id.
     */
    UUID id();

    /**
     * @param index the elasticsearch index for which to retrieve the timelineId
     * @return the timelineId corresponding to the given elasticsearch index.
     */
    UUID getTimeline(String index);

    /**
     * @param timelineId the timelineId for which to retrieve the index
     * @return the elasticsearch index associated with the timelineId
     */
    String getIndex(UUID timelineId);

    /**
     * Initializes startTimestamp
     */
    void start();

    /**
     * Initializes endTimestamp
     */
    void stop();

    /**
     * @return the list of elasticsearch indices used to produce the timelines for this execution
     */
    Set<String> inputIndices();

    /**
     * @return The ids of the timelines produced in this execution
     */
    Set<UUID> timelineIds();

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
    ExternalArtifact xes();

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

    void registerTimeline(String index, UUID timeline);

}
