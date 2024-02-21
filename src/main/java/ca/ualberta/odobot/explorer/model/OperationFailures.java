package ca.ualberta.odobot.explorer.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class OperationFailures extends ArrayList<OperationFailure> {

    List<FailPair> failureDistribution = new ArrayList<>();

    private class FailPair{

        public FailPair(UUID failedOperationId, int operationsSinceLastFailure){
           this.failedOperationId = failedOperationId;
           this.operationsSinceLastFailure = operationsSinceLastFailure;
        }
        private UUID failedOperationId;
        private int operationsSinceLastFailure;

        public JsonObject toJson(){
            JsonObject result = new JsonObject()
                    .put("opId", failedOperationId.toString())
                    .put("operationsSinceLastFailure", operationsSinceLastFailure);
            return result;
        }
    }

    int operationsSinceLastFailure = 0;

    public void operationSucceeded(){
        operationsSinceLastFailure++;
    }

    public boolean add(OperationFailure failure){
        failureDistribution.add(new FailPair(failure.failedOperation.getId(), operationsSinceLastFailure));
        operationsSinceLastFailure = 0;
        return super.add(failure);
    }


    /**
     * @return A JsonObject breaking down the failure frequency by OperationType
     */
    public JsonObject countByType(){

        Map<Operation.OperationType, Integer> table = new HashMap<>();

        forEach(failure->{
            Integer count = table.getOrDefault(failure.failedOperation.getType(), 0);
            table.put(failure.failedOperation.getType(), count+1);
        });

        return table.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(JsonObject::new, (json, element)->{
                        json.put(element.getKey().toString(), element.getValue());
                } ,JsonObject::mergeIn);

    }

    /**
     * @return A JsonObject breaking down the failure frequency by Resource
     */
    public JsonObject countByResource(){

        Map<Class, Integer> table = new HashMap<>();

        forEach(failure->{
            Integer count = table.getOrDefault(failure.failedOperation.getResource(), 0);
            table.put(failure.failedOperation.getResource(), count+1);
        });

        return table.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(JsonObject::new, (json, element)->{
                    json.put(element.getKey().getName(), element.getValue());
                } ,JsonObject::mergeIn);

    }

    /**
     * @return A JsonObject breaking down the failure frequency by the selenium exception
     */
    public JsonObject countByException(){
        Map<Class, Integer> table = new HashMap<>();

        forEach(failure->{
            Integer count = table.getOrDefault(failure.exception.getClass(), 0);
            table.put(failure.exception.getClass(), count+1);
        });

        return table.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(JsonObject::new, (json, element)->{
                    json.put(element.getKey().getName(), element.getValue());
                } ,JsonObject::mergeIn);
    }


    /**
     * @return A JsonObject breaking down the failure frequency by the url at exception time
     */
    public JsonObject countByUrl(){
        Map<String, Integer> table = new HashMap<>();

        forEach(failure->{
            Integer count = table.getOrDefault(failure.url, 0);
            table.put(failure.url, count+1);
        });

        return table.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(JsonObject::new, (json, element)->{
                    json.put(element.getKey(), element.getValue());
                } ,JsonObject::mergeIn);
    }

    public JsonObject fullReport(){

        JsonObject byOperationType = countByType();
        JsonObject byExceptionType = countByException();
        JsonObject byResource = countByResource();
        JsonObject byUrl = countByUrl();
        JsonArray failures = stream().map(OperationFailure::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
        JsonArray distribution = failureDistribution.stream().map(FailPair::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);


        JsonObject report = new JsonObject()
                .put("byOperationType", byOperationType)
                .put("byException", byExceptionType)
                .put("byResource", byResource)
                .put("byUrl", byUrl)
                .put("distribution", distribution)
                .put("failures", failures);

        return report;

    }




}
