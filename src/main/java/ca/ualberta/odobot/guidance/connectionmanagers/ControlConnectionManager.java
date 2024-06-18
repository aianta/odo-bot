package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.Request;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ControlConnectionManager extends AbstractConnectionManager implements ConnectionManager{

    private static final Logger log = LoggerFactory.getLogger(ControlConnectionManager.class);

    private Request request;

    private BiConsumer<String,String> newRequestTargetNodeConsumer;

    public ControlConnectionManager(Request request){
        this.request = request;
    }



    /**
     * Handles the types of messages the server receives on the control socket.
     * @param message
     */
    public void onMessage(JsonObject message){
        log.info("ControlConnectionManager onMessage invoked! - {}", message.getString("type"));
        switch (message.getString("type")){
            case "PATHS_REQUEST":
                request.setTargetNode(message.getString("targetNode"));
                request.setUserLocation(message.getString("userLocation"));
                log.info("Target node is: {}", request.getTargetNode());
                newRequestTargetNodeConsumer.accept(request.getTargetNode(), message.getString("userLocation"));
                break;
            case "STOP_GUIDANCE_REQUEST":

                //TODO -> this looks like a good place for a composite future
                //Stop transmitting
                request.getEventConnectionManager().stopTransmitting();

                //Clear any existing navigation options being displayed.
                request.getGuidanceConnectionManager().clearNavigationOptions().onSuccess(done->{
                    //Close connections
                    request.getControlConnectionManager().close();
                    request.getGuidanceConnectionManager().close();
                    request.getEventConnectionManager().close();

                    //Clear connection managers
                    request.clearConnectionManagers();

                    //Remove request from request map.
                    WebSocketConnection.requestMap.remove(request.id().toString());
                });


                //Clear the message queues... I think we want this.
//                request.getEventConnectionManager().clearMessageQueue();
//                request.getGuidanceConnectionManager().clearMessageQueue();
//                clearMessageQueue();
                break;
        }
    }

    public BiConsumer<String,String> getNewRequestTargetNodeConsumer() {
        return newRequestTargetNodeConsumer;
    }

    public void setNewRequestTargetNodeConsumer(BiConsumer<String,String> newRequestTargetNodeConsumer) {
        this.newRequestTargetNodeConsumer = newRequestTargetNodeConsumer;
    }
}
