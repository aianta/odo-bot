package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogUINetworkEventMapper extends JsonMapper<NetworkEvent> {

    private static final Logger log = LoggerFactory.getLogger(LogUINetworkEventMapper.class);


    @Override
    public NetworkEvent map(JsonObject event) {

        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject timeData = event.getJsonObject("timestamps");

        NetworkEvent result = new NetworkEvent();

        result.setLogUISessionId(event.getString("sessionID"));

        if(eventDetails.containsKey("requestHeaders") && !eventDetails.getString("requestHeaders").equals("null")){
            result.setRequestHeaders(new JsonObject(eventDetails.getString("requestHeaders")));
        }

        if(eventDetails.containsKey("responseHeaders") && !eventDetails.getString("responseHeaders").equals("null")){
            result.setResponseHeaders(new JsonObject(eventDetails.getString("responseHeaders")));
        }

        //Extract request body
        if(eventDetails.containsKey("requestBody") && !eventDetails.getString("requestBody").equals("null")){
            try{
                JsonObject request = new JsonObject(eventDetails.getString("requestBody"));
                result.setRequestObject(request);
            }catch (DecodeException de){
                JsonArray requestArray = new JsonArray(eventDetails.getString("requestBody"));
                result.setRequestArray(requestArray);
            }
        }


        if(eventDetails.containsKey("responseBody")){
            try{
                JsonObject response = new JsonObject(eventDetails.getString("responseBody"));
                result.setResponseObject(response);
            }catch (DecodeException de){
                JsonArray responseArray = new JsonArray(eventDetails.getString("responseBody"));
                result.setResponseArray(responseArray);
            }
        }

        long timestamp = Long.parseLong(eventDetails.getString("timeStamp"));
        int requestId = Integer.parseInt(eventDetails.getString("requestId"));
        result.setMillisecondTimestamp(timestamp);
        result.setRequestId(requestId);

        String documentUrl = eventDetails.getString("documentUrl");
        String url = eventDetails.getString("url");
        String type = eventDetails.getString("type");

        result.setType(type);
        result.setDocumentUrl(documentUrl);
        result.setMethod(eventDetails.getString("method"));
        result.setUrl(url);

        return  result;

    }
}
