package ca.ualberta.odobot.task;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.GraphDB;
import ca.ualberta.odobot.semanticflow.navmodel.Localizer;
import ca.ualberta.odobot.semanticflow.navmodel.NavPathsConstructor;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.snippet2xml.Snippet2XMLVerticle;
import ca.ualberta.odobot.snippet2xml.impl.Snippet2XMLServiceImpl;
import ca.ualberta.odobot.taskplanner.TaskPlannerVerticle;
import ca.ualberta.odobot.taskplanner.impl.TaskPlannerServiceImpl;
import io.vertx.core.json.JsonObject;

/**
 * The services shared by every task in the process, handed to each {@link TaskVerticle} instead of read from statics.
 * They are read-only while tasks run, or safe to call from several threads.
 *
 * <p>Services that own a single resource (SQLite, the vector index, telemetry) are not here. Each task verticle builds
 * its own event bus proxy to them.</p>
 *
 * @param graphDb the embedded Neo4j database. Transactions are bound to a thread.
 * @param plannerPrototype copied per task with {@code withScope}, so the copy counts the task's LLM calls.
 * @param snippet2XmlPrototype copied per task with {@code withScope}.
 * @param guidanceConfig config/guidance.yaml, which names the keystore of the tasks' listeners.
 */
public record Platform(
        GraphDB graphDb,
        Neo4JUtils neo4j,
        Localizer localizer,
        NavPathsConstructor paths,
        TaskPlannerServiceImpl plannerPrototype,
        Snippet2XMLServiceImpl snippet2XmlPrototype,
        JsonObject guidanceConfig
) {

    /**
     * @return the platform as the service verticles have published it so far. The charted services may still be
     * null if their verticles haven't finished starting, see {@link #chartedReady}.
     */
    public static Platform current(JsonObject guidanceConfig){
        return new Platform(
                LogPreprocessor.graphDB,
                LogPreprocessor.neo4j,
                LogPreprocessor.localizer,
                LogPreprocessor.pathsConstructor,
                (TaskPlannerServiceImpl) TaskPlannerVerticle.service,
                (Snippet2XMLServiceImpl) Snippet2XMLVerticle.snippet2XML,
                guidanceConfig
        );
    }

    /**
     * @return true if everything a charted agent needs is available.
     */
    public boolean chartedReady(){
        return graphDb != null && neo4j != null && localizer != null && paths != null && plannerPrototype != null && snippet2XmlPrototype != null;
    }
}
