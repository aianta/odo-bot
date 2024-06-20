package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.Request;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.BiConsumer;

public class ControlConnectionManager extends AbstractConnectionManager implements ConnectionManager{

    private static final Logger log = LoggerFactory.getLogger(ControlConnectionManager.class);

    public ControlConnectionManager(OdoClient client){
        super(client);
    }


    /**
     * Handles the types of messages the server receives on the control socket.
     * @param message
     */
    public void onMessage(JsonObject message){
        log.info("ControlConnectionManager onMessage invoked! - {}", message.getString("type"));
        switch (message.getString("type")){
            case "PATHS_REQUEST":
                Request request = new Request(UUID.fromString(message.getString("pathsRequestId")));
                request.setTargetNode(message.getString("targetNode"));
                request.setUserLocation(message.getString("userLocation"));

                client.getRequestManager().addNewRequest(request);


                break;
            case "STOP_GUIDANCE_REQUEST":

                //TODO -> What, if any, responsibilities does the server have in this case.
                break;
        }
    }



}
