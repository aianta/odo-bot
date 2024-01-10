package ca.ualberta.odobot.semanticflow.model.semantictrace;

import ca.ualberta.odobot.semanticflow.model.DbOps;
import io.vertx.core.json.JsonObject;

import java.util.UUID;


public class SemanticLabel {


    UUID id = UUID.randomUUID();

    String subject;


    JsonObject annotations = new JsonObject();

    JsonObject subjectSupport = new JsonObject();

    String verb;

    JsonObject verbSupport = new JsonObject();

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getVerb() {
        return verb;
    }

    public void setVerb(String verb) {
        this.verb = verb;
    }

    public JsonObject getAnnotations() {
        return annotations;
    }

    public JsonObject getSubjectSupport() {
        return subjectSupport;
    }

    public JsonObject getVerbSupport() {
        return verbSupport;
    }

    public String toString(){
        return verb + " " + subject;
    }

    public UUID getId() {
        return id;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("id", getId())
                .put("label", toString())
                .put("annotations",getAnnotations())
                .put("subject", subject)
                .put("verb", verb)
                .put("subjectSupport", getSubjectSupport())
                .put("verbSupport", getVerbSupport());

        return result;
    }

}
