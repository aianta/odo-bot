package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkEventMapper extends JsonMapper<NetworkEvent>{
    private static final Logger log = LoggerFactory.getLogger(NetworkEventMapper.class);


    public NetworkEvent map(JsonObject event) {

        NetworkEvent result = new NetworkEvent();
        result.setFlightId(event.getString("flightID"));
        result.setLogUISessionId(event.getString("sessionID"));

        if(event.containsKey("eventDetails_requestBody") && !event.getString("eventDetails_requestBody").equals("null")){

            try{
                JsonObject request = new JsonObject(event.getString("eventDetails_requestBody"));
                result.setRequestObject(request);
            }catch (DecodeException de){
                JsonArray requestArray = new JsonArray(event.getString("eventDetails_requestBody"));
                result.setRequestArray(requestArray);
            }

        }

        if(event.containsKey("eventDetails_responseBody")){
            try{
                JsonObject response = new JsonObject(event.getString("eventDetails_responseBody"));
                result.setResponseObject(response);
            }catch (DecodeException de){


                JsonArray responseArray = new JsonArray(event.getString("eventDetails_responseBody"));
                result.setResponseArray(responseArray);
            }


        }

        long timestamp = Long.parseLong(event.getString("eventDetails_timeStamp"));
        int requestId = event.getInteger("eventDetails_requestId");
        result.setMillisecondTimestamp(timestamp);
        result.setRequestId(requestId);

        String documentUrl = event.getString("eventDetails_documentUrl");
        String url = event.getString("eventDetails_url");
        String type = event.getString("eventDetails_type");

        result.setType(type);
        result.setDocumentUrl(documentUrl);
        result.setMethod(event.getString("eventDetails_method"));
        result.setUrl(url);

        return result;

    }

    private static String getHeaderField(String field, JsonArray headers){
        JsonObject targetHeader = headers.stream()
                .map(entry->(JsonObject)entry)
                .filter(header->header.getString("name").equals(field))
                .findFirst().orElse(null);
        return targetHeader != null?targetHeader.getString("value"):null;

    }

}
