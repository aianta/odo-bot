package ca.ualberta.odobot.snippets;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;


import java.util.UUID;

@DataObject
public class Snippet {

    public enum Type {
        PARENT,CHILD
    }

    private UUID id;
    private String snippet;

    private String dynamicXpath;
    private Type type;

    private String baseURI; //This one is optional, but should exist for input changes and button clicks.

    public String getDynamicXpath() {
        return dynamicXpath;
    }

    public Snippet setDynamicXpath(String dynamicXpath) {
        this.dynamicXpath = dynamicXpath;
        return this;
    }

    public static Snippet fromRow(Row row){
        Snippet result = new Snippet();
        result.setId(row.getUUID("id"));
        result.setSnippet(row.getString("snippet"));
        result.setType(Type.valueOf(row.getString("snippet_type").toUpperCase()));
        result.setDynamicXpath(row.getString("dynamic_xpath"));
        if(row.getString("base_uri") != null){
            result.setBaseURI(row.getString("base_uri"));
        }
        return result;
    }

    public static Snippet fromJson(JsonObject json){
        Snippet result = new Snippet();
        result.setId(UUID.fromString(json.getString("id")));
        result.setSnippet(json.getString("snippet"));
        result.setType(Type.valueOf(json.getString("type")));
        result.setDynamicXpath(json.getString("dynamicXpath"));
        if(json.containsKey("baseURI")){
            result.setBaseURI(json.getString("baseURI"));
        }
        return result;
    }

    public Snippet(){};

    public Snippet(JsonObject data){
        setId(UUID.fromString(data.getString("id")));
        setSnippet(data.getString("snippet"));
        setType(Type.valueOf(data.getString("type")));
        setDynamicXpath(data.getString("dynamicXpath"));
        if(data.containsKey("baseURI")){
            setBaseURI(data.getString("baseURI"));
        }
    }

    public UUID getId() {
        return id;
    }

    public Snippet setId(UUID id) {
        this.id = id;
        return this;
    }

    public String getSnippet() {
        return snippet;
    }

    public Snippet setSnippet(String snippet) {
        this.snippet = snippet;
        return this;
    }

    public Type getType() {
        return type;
    }

    public Snippet setType(Type type) {
        this.type = type;
        return this;
    }

    public String getBaseURI() {
        return baseURI;
    }

    public Snippet setBaseURI(String baseURI) {
        this.baseURI = baseURI;
        return this;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("id", getId().toString())
                .put("snippet", getSnippet())
                .put("type", getType().toString())
                .put("dynamicXpath", getDynamicXpath())
        ;

        if(getBaseURI() != null){
            result.put("baseURI", baseURI);
        }

        return result;
    }
}
