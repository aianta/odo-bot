package ca.ualberta.odobot.snippet2xml;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import java.util.UUID;

@DataObject
public class SemanticObject {

    private UUID id;
    private UUID schemaId;

    private UUID snippetId;

    private String object;

    public SemanticObject(){};

    public static SemanticObject fromRow(Row row){
        SemanticObject object = new SemanticObject();
        object.setId(row.getUUID("id"));
        object.setSnippetId(row.getUUID("snippet_id"));
        object.setSchemaId(row.getUUID("schema_id"));
        object.setObject(row.getString("object"));

        return object;
    }

    public SemanticObject(JsonObject data){
        setId(UUID.fromString(data.getString("id")));
        setSchemaId(UUID.fromString(data.getString("schemaId")));
        setSnippetId(UUID.fromString(data.getString("snippetId")));
        setObject(data.getString("object"));
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("id", getId().toString())
                .put("schemaId", getSchemaId().toString())
                .put("snippetId", getSnippetId().toString())
                .put("object", getObject());

        return result;
    }
    public UUID getId() {
        return id;
    }

    public SemanticObject setId(UUID id) {
        this.id = id;
        return this;
    }

    public UUID getSchemaId() {
        return schemaId;
    }

    public SemanticObject setSchemaId(UUID schemaId) {
        this.schemaId = schemaId;
        return this;
    }

    public UUID getSnippetId() {
        return snippetId;
    }

    public SemanticObject setSnippetId(UUID snippetId) {
        this.snippetId = snippetId;
        return this;
    }

    public String getObject() {
        return object;
    }

    public SemanticObject setObject(String object) {
        this.object = object;
        return this;
    }
}
