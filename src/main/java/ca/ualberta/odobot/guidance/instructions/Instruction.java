package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class Instruction {

    private Set<String> alternateXpaths =  new HashSet<>();

    private String sourceNodeId;

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
