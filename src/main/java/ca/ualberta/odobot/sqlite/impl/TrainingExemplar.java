package ca.ualberta.odobot.sqlite.impl;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import java.util.Arrays;
import java.util.UUID;


public record TrainingExemplar(
        UUID id,
        String source,
        double [] featureVector,

        int [] labels,
        String datasetName,

        JsonObject extras
) {

    static TrainingExemplar fromRow(Row row){

        return new TrainingExemplar(
                UUID.fromString(row.getString("id")),
                row.getString("source"),
                new JsonArray(row.getString("feature_vector")).stream().mapToDouble(entry->Double.parseDouble((String)entry)).toArray(),
                new JsonArray(row.getString("label")).stream().mapToInt(entry->Integer.parseInt((String)entry)).toArray(),
                row.getString("dataset_name"),
                new JsonObject(row.getString("extras"))
        );
    }

    public static TrainingExemplar fromJson(JsonObject json){
        return new TrainingExemplar(
                UUID.fromString(json.getString("id")),
                json.getString("source"),
                json.getJsonArray("featureVector").stream().mapToDouble(entry->(double) entry).toArray(),
                json.getJsonArray("labels").stream().mapToInt(entry->(int) entry).toArray(),
                json.getString("datasetName"),
                json.getJsonObject("extras")
        );
    }
    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("id", id().toString())
                .put("source", source())
                .put("featureVector", Arrays.stream(featureVector()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("labels", Arrays.stream(labels()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("datasetName", datasetName())
                .put("featureVectorSize", featureVector().length)
                .put("extras", extras)
        ;
        return result;
    }


}
