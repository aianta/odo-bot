package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.navmodel.GraphDB;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.json.JsonObject;

public abstract class AbstractAgent implements IAgent{


    SqliteService sqlite;
    GraphDB graphDB;
    Neo4JUtils neo4J;
    JsonObject task;


    protected static abstract class Builder{

        SqliteService sqlite;
        GraphDB graphDB;
        Neo4JUtils neo4J;
        JsonObject task;


        public Builder sqlite(SqliteService sqlite) {
            this.sqlite = sqlite;
            return this;
        }

        public Builder graphDb(GraphDB graphDB) {
            this.graphDB = graphDB;
            return this;
        }

        public Builder neo4J(Neo4JUtils neo4J) {
            this.neo4J = neo4J;
            return this;
        }

        public Builder task(JsonObject task) {
            this.task = task;
            return this;
        }

        boolean validate(){
            return !(graphDB == null || neo4J == null || task == null || sqlite == null);
        }


        abstract AbstractAgent build();
    }


    public abstract void observationHandler(TimelineEntity timelineEntity);


}
