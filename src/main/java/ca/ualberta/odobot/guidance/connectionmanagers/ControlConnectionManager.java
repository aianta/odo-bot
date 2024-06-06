package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.Request;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class ControlConnectionManager implements ConnectionManager{

    private static final Logger log = LoggerFactory.getLogger(ControlConnectionManager.class);

    private Request request;

    private Consumer<String> newRequestTargetNodeConsumer;

    public ControlConnectionManager(Request request){
        this.request = request;
    }



    /**
     * Handles the types of messages the server receives on the control socket.
     * @param message
     */
    public void onMessage(JsonObject message){
        switch (message.getString("type")){
            case "PATHS_REQUEST":
                request.setTargetNode(message.getString("targetNode"));
                log.info("Target node is: {}", request.getTargetNode());
                newRequestTargetNodeConsumer.accept(request.getTargetNode());
                break;
            case "STOP_GUIDANCE_REQUEST":
                break;
        }
    }

    @Override
    public void updateConnection(WebSocketConnection connection) {
        connection.setMessageConsumer(this::onMessage);
    }

    public Consumer<String> getNewRequestTargetNodeConsumer() {
        return newRequestTargetNodeConsumer;
    }

    public void setNewRequestTargetNodeConsumer(Consumer<String> newRequestTargetNodeConsumer) {
        this.newRequestTargetNodeConsumer = newRequestTargetNodeConsumer;
    }
}
