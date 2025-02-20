package ca.ualberta.odobot.explorer.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

public class OdoBotExecutionRequest extends JsonObject {

    public String id(){
        return getString("id");
    }

    public OdoBotExecutionRequest id(String id){
        put("id", id);
        return this;
    }

    public OdoBotExecutionRequest id(UUID id){
        put("id", id.toString());
        return this;
    }

    public OdoBotExecutionRequest target(String id){
        return target(UUID.fromString(id));
    }

    public OdoBotExecutionRequest target(UUID id){
        put("target", id.toString());
        return this;
    }

    public UUID target(){
        return UUID.fromString(getString("target"));
    }

    public OdoBotExecutionRequest userLocation(String location){
        put("userLocation", location);
        return this;
    }


    public OdoBotExecutionRequest addSchemaParameter(String id, String query){
        return addSchemaParameter(UUID.fromString(id), query);
    }
    public OdoBotExecutionRequest addSchemaParameter(UUID id, String query){
        JsonObject param = new JsonObject()
                .put("id", id.toString())
                .put("type", "SchemaParameter")
                .put("query", query);
        return addParameter(param);
    }

    public OdoBotExecutionRequest addInputParameter(String id, String value){
        return addInputParameter(UUID.fromString(id), value);
    }
    public OdoBotExecutionRequest addInputParameter(UUID id, String value){
        JsonObject param = new JsonObject()
                .put("id", id.toString())
                .put("type", "InputParameter")
                .put("value", value);

        return addParameter(param);
    }

    public OdoBotExecutionRequest addParameter(JsonObject param){
        if(!containsKey("parameters")){
            put("parameters", new JsonArray());
        }

        getJsonArray("parameters").add(param);
        return this;
    }

    public JsonArray getParameters(){
        return getJsonArray("parameters");
    }

}
