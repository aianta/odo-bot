package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;

public abstract class AbstractConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(AbstractConnectionManager.class);

    protected Queue<String> queue = new LinkedList<String>();

    protected WebSocketConnection connection = null;

    protected OdoClient client;

    public AbstractConnectionManager(OdoClient client){
        this.client = client;
    }


    public void clearMessageQueue(){
        queue.clear();
    }

    public void close(){
        if(connection != null){
            connection.close();
        }
    }

    public void updateConnection(WebSocketConnection connection){
        this.connection = connection;
        this.connection.setMessageConsumer(this::onMessage);
        log.info("Clearing queue: {} messages {}", queue.size(), getClass().getName());
        while (!queue.isEmpty()){
            if(!this.send(queue.poll())){
                break;
            }
        }


    }

    public abstract void onMessage(JsonObject message);

    protected boolean send(String data){
        if(connection != null && connection.isConnected()){
            log.info("Message sent over websocket! {}", getClass().getName());
            connection.send(data);
            return true;
        }else{

            queue.add(data);
            log.info("added message to queue! Queue size: {} {}", queue.size(), getClass().getName());
            return false;
        }
    }

    protected boolean send(JsonObject data){
        log.info("Sending: \n{}",data.encodePrettily());
        return send(data.encode());
    }


    protected JsonObject makeNotifyPathCompleteRequest(String source){
        JsonObject notifyPathCompleteRequest = new JsonObject()
                .put("type", "PATH_COMPLETE")
                .put("source", source)
                //resolve the paths request id, this can either be a guidance request (Request) or execution request (ExecutionRequest) TODO: refactor this
                .put("pathsRequestId", client.getRequestManager().getActiveExecutionRequest().getId().toString());

        return notifyPathCompleteRequest;
    }
}
