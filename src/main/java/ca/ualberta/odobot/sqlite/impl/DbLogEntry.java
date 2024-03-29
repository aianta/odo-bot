package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.sqlite.LogParser;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import java.time.ZonedDateTime;

public record DbLogEntry(
        String key,
        ZonedDateTime timestamp,
        long timestampMilli,
        String type, //WRITE/READ
        String command, //INSERT, UPDATE, DROP
        String objectType,  //TABLE
        String objectName, //the table name
        String statement,
        String parameter
        ) {

    public static DbLogEntry fromRow(Row row){

        DbLogEntry result = new DbLogEntry(
                row.getString("key_value"),
                ZonedDateTime.parse(row.getString("timestamp"), LogParser.timestampFormat),
                row.getLong("timestamp_milli"),
                row.getString("type"),
                row.getString("command"),
                row.getString("object_type"),
                row.getString("object_name"),
                row.getString("statement"),
                row.getString("parameter")
        );

        return result;

    }

    public static DbLogEntry fromJson(JsonObject json){

        DbLogEntry result = new DbLogEntry(
                json.getString("key"),
                ZonedDateTime.parse(json.getString("timestamp"), LogParser.timestampFormat),
                json.getLong("timestampMilli"),
                json.getString("type"),
                json.getString("command"),
                json.getString("objectType"),
                json.getString("objectName"),
                json.getString("statement"),
                json.getString("parameter")
        );

        return result;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("key", key)
                .put("timestamp", timestamp.format(LogParser.timestampFormat))
                .put("timestampMilli", timestampMilli)
                .put("type", type)
                .put("command", command)
                .put("objectType", objectType)
                .put("objectName", objectName)
                .put("statement", statement)
                .put("parameter", parameter);


        return result;
    }

}
