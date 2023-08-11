package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkEventMapper extends JsonMapper<NetworkEvent>{
    private static final Logger log = LoggerFactory.getLogger(NetworkEventMapper.class);


    public NetworkEvent map(JsonObject event) {

        NetworkEvent result = new NetworkEvent();
        result.setServerIPAddress(event.getString("eventDetails_serverIPAddress"));
        result.setFlightId(event.getString("flightID"));
        result.setLogUISessionId(event.getString("sessionID"));

        JsonObject response = new JsonObject(event.getString("eventDetails_response"));
        result.setResponse(response);
        result.setStatusCode(response.getInteger("status"));


        JsonObject request = new JsonObject(event.getString("eventDetails_request"));
        JsonArray requestHeaders = request.getJsonArray("headers");
        result.setRequest(request);
        result.setMethod(request.getString("method"));
        result.setUrl(request.getString("url"));
        result.setHost(getHeaderField("Host", requestHeaders));
        result.setUserAgent(getHeaderField("User-Agent", requestHeaders));

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
