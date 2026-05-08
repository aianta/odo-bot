package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

public abstract class AbstractConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(AbstractConnectionManager.class);

    protected Queue<String> queue = new LinkedList<String>();

    protected WebSocketConnection connection = null;

    protected OdoClient client;

    protected List<JsonObject> history = new ArrayList<>();


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

    protected void addHistory(JsonObject data){
        data.put("timestamp", Instant.now().toString());
        history.add(data);
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

    protected void saveHistory(String source){
        if(client.getRequestManager().getEvalId() != null){
            String fileName = "./%s/%s-%s-history.json".formatted("execution_events", client.getRequestManager().getEvalId(),source).replaceAll("\\|","-");
            File fout = new File(fileName);
            try(FileWriter fw = new FileWriter(fout);
                BufferedWriter bw = new BufferedWriter(fw);
            ){
                String output = history.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily();
                bw.write(output);
                bw.flush();
            }catch(IOException e){
                log.error("Failed to save history for {}: {}", source, e.getMessage());
            }
        }

    }
}
