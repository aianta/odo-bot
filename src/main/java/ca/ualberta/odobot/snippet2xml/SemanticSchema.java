package ca.ualberta.odobot.snippet2xml;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import java.util.UUID;

@DataObject
public class SemanticSchema {

    private UUID id;

    private String name;

    private String schema;

    public static SemanticSchema fromRow(Row row){
        SemanticSchema result = new SemanticSchema();
        result.setSchema(row.getString("schema"));
        result.setId(row.getUUID("id"));
        result.setName(row.getString("name"));
        return result;
    }

    public SemanticSchema(){}
    public SemanticSchema(JsonObject data){
        setId(UUID.fromString(data.getString("id")));
        setName(data.getString("name"));
        setSchema(data.getString("schema"));
    }

    public UUID getId() {
        return id;
    }

    public SemanticSchema setId(UUID id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public SemanticSchema setName(String name) {
        this.name = name;
        return this;
    }

    public String getSchema() {
        return schema;
    }

    public SemanticSchema setSchema(String schema) {
        this.schema = schema;
        return this;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("id", getId().toString())
                .put("schema", getSchema())
                .put("name", getName());
        return result;
    }
}
