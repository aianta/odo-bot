package ca.ualberta.odobot.semanticflow.model.semantictrace;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class SemanticTrace extends ArrayList<SemanticLabel> {

    private UUID id = UUID.randomUUID();

    public SemanticTrace(){}
    public SemanticTrace(List<SemanticLabel> labels){
        super(labels);
    }


    private JsonObject annotations = new JsonObject();

    public UUID getId(){
        if (id == null){
            id = UUID.randomUUID();
        }
        return id;
    }


    public void setSourceIndex(String index){
        annotations.put("sourceIndex", index);
    }

    public void setConstructionStrategy(String constructionStrategy) {
        annotations.put("constructionStrategy", constructionStrategy);
    }

    public void setSourceTimelineId(UUID sourceTimelineId) {
        annotations.put("sourceTimelineId", sourceTimelineId.toString());

    }

    public JsonObject getAnnotations() {
        if(annotations == null){ //Init annotations if null
            annotations = new JsonObject().put("id", getId().toString());
        }
        return annotations;
    }

    public void setAnnotations(JsonObject annotations) {
        this.annotations = annotations;
    }

    public JsonObject toJson(){

        JsonArray labels = stream().map(SemanticLabel::toJson).collect(
                JsonArray::new, JsonArray::add, JsonArray::addAll
        );

        JsonObject result = new JsonObject()
                .put("id", getId().toString())
                .put("annotations", getAnnotations())
                .put("labels", labels);


        return result;
    }
}
