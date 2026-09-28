package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.semanticflow.navmodel.GraphDB;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public abstract class AbstractAgent implements IAgent{

    private static final Logger log = LoggerFactory.getLogger(AbstractAgent.class);

    protected SqliteService sqlite;
    protected GraphDB graphDB;
    protected Neo4JUtils neo4J;
    protected JsonObject task;

    /**
     * Folder and evaluation id used to name the artifacts this agent writes.
     */
    protected String artifactDir;
    protected String evalId;

    protected Consumer<Instruction> instructionConsumer;

    protected boolean stopped = false;

    @Override
    public void setInstructionConsumer(Consumer<Instruction> consumer) {
        this.instructionConsumer = consumer;
    }

    /**
     * Hand an instruction to the harness. Instructions are dropped once the agent has been stopped.
     */
    protected void emit(Instruction instruction){
        if(stopped || instructionConsumer == null){
            log.info("Agent is stopped or has no instruction consumer, dropping instruction: {}", instruction);
            return;
        }
        instructionConsumer.accept(instruction);
    }

    protected String artifactPath(String suffix){
        return "%s/%s-%s".formatted(artifactDir, evalId, suffix).replaceAll("\\|","-");
    }

    public static abstract class Builder<A extends AbstractAgent, B extends Builder<A, B>>{

        SqliteService sqlite;
        GraphDB graphDB;
        Neo4JUtils neo4J;
        JsonObject task;
        String artifactDir;
        String evalId;

        public B sqlite(SqliteService sqlite) {
            this.sqlite = sqlite;
            return self();
        }

        public B graphDb(GraphDB graphDB) {
            this.graphDB = graphDB;
            return self();
        }

        public B neo4J(Neo4JUtils neo4J) {
            this.neo4J = neo4J;
            return self();
        }

        public B task(JsonObject task) {
            this.task = task;
            return self();
        }

        public B artifactDir(String artifactDir){
            this.artifactDir = artifactDir;
            return self();
        }

        public B evalId(String evalId){
            this.evalId = evalId;
            return self();
        }

        protected boolean validate(){
            return !(graphDB == null || neo4J == null || task == null || sqlite == null || artifactDir == null || evalId == null);
        }

        public A build(){
            if(!validate()){
                throw new IllegalStateException("Cannot build agent, missing required fields.");
            }

            A agent = create();
            agent.sqlite = sqlite;
            agent.graphDB = graphDB;
            agent.neo4J = neo4J;
            agent.task = task;
            agent.artifactDir = artifactDir;
            agent.evalId = evalId;
            return agent;
        }

        /**
         * @return a new agent with the subclass specific fields set.
         */
        protected abstract A create();

        protected abstract B self();
    }

}
