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

        //Extract request headers
        if(event.containsKey("eventDetails_requestHeaders") && !event.getString("eventDetails_requestHeaders").equals("null")){

            result.setRequestHeaders(new JsonObject(event.getString("eventDetails_requestHeaders")));

        }

        //Extract response headers
        if(event.containsKey("eventDetails_responseHeaders") && !event.getString("eventDetails_responseHeaders").equals("null")){

            result.setResponseHeaders(new JsonObject(event.getString("eventDetails_responseHeaders")));

        }


        //Extract request body
        if(event.containsKey("eventDetails_requestBody") && !event.getString("eventDetails_requestBody").equals("null")){

            try{
                JsonObject request = new JsonObject(event.getString("eventDetails_requestBody"));
                result.setRequestObject(request);
            }catch (DecodeException de){
                try{
                    JsonArray requestArray = new JsonArray(event.getString("eventDetails_requestBody"));
                    result.setRequestArray(requestArray);
                }catch (DecodeException de2){
                    log.error("Request body could not be decoded as JsonObject or JsonArray. Body value was: {}", event.getString("eventDetails_requestBody"));
                }

            }

        }


        //Extract response body
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
