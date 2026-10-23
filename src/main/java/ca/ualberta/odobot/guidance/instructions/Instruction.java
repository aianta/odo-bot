package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.trajectory.AgentTrace;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class Instruction {

    private Set<String> alternateXpaths =  new HashSet<>();

    private String sourceNodeId;

    /**
     * What the agent thought when it emitted this instruction, or null. It is saved with the task's trajectory and never
     * sent to OdoX, so it is not part of {@link #toJson()}.
     */
    private AgentTrace agentTrace;

    public AgentTrace getAgentTrace() {
        return agentTrace;
    }

    public Instruction setAgentTrace(AgentTrace agentTrace) {
        this.agentTrace = agentTrace;
        return this;
    }

    public Set<String> alternateXpaths() {
        return alternateXpaths;
    }

    public Instruction addAlternateXpath(String xpath){
        alternateXpaths.add(xpath);
        return this;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public Instruction setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
        return this;
    }



    public abstract boolean equals(Object obj);

    public abstract int hashCode();

    public abstract String toString();

    public JsonObject toJson() {
        return new JsonObject().put("sourceNodeId", sourceNodeId);
    }

}
