package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.Channel;
import ca.ualberta.odobot.guidance.ChannelConnection;
import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles one channel of OdoX's socket, see {@link Channel}.
 *
 * <p>Messages sent while the channel is closed are queued, and go out in order once it opens again. Replies are matched
 * to their requests by id: OdoX answers a message with one whose {@code replyTo} is the message's {@code id}.</p>
 */
public abstract class AbstractConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(AbstractConnectionManager.class);

    protected final Channel channel;

    protected Queue<String> queue = new LinkedList<String>();

    protected ChannelConnection connection = null;

    protected OdoClient client;

    protected List<JsonObject> history = new ArrayList<>();

    /**
     * The requests waiting for OdoX's reply, by id.
     */
    private final Map<String, Promise<JsonObject>> pendingReplies = new ConcurrentHashMap<>();


    public AbstractConnectionManager(OdoClient client, Channel channel){
        this.client = client;
        this.channel = channel;
    }


    public void clearMessageQueue(){
        queue.clear();
    }

    public void updateConnection(ChannelConnection connection){
        this.connection = connection;
        flushQueue();
    }

    /**
     * Send the messages queued while the channel was closed, if it is open now.
     */
    public void flushQueue(){
        if(!queue.isEmpty()){
            log.info("Clearing queue: {} messages {}", queue.size(), getClass().getName());
        }
        while (!queue.isEmpty() && isOpen()){
            String data = queue.poll();
            connection.send(data);
            onSent(data);
        }
    }

    protected boolean isOpen(){
        return connection != null && connection.isConnected();
    }

    /**
     * Handle a message OdoX sent on this channel. A reply completes the request it answers; any other message goes to
     * {@link #onMessage}.
     */
    public void dispatch(JsonObject message){
        String replyTo = message.getString("replyTo");
        if(replyTo == null){
            onMessage(message);
            return;
        }

        Promise<JsonObject> request = pendingReplies.remove(replyTo);
        if(request == null){
            log.info("Dropping {} {}, no request {} is waiting for it, e.g. because the task ended.", channel.name, message.getString("type"), replyTo);
            return;
        }
        onReply(message);
        request.complete(message);
    }

    public abstract void onMessage(JsonObject message);

    /**
     * Called with each reply that answers a pending request, before the request completes.
     */
    protected void onReply(JsonObject message){
    }

    /**
     * Send a message and wait for OdoX's reply to it.
     * @return completes with the reply.
     */
    public Future<JsonObject> request(JsonObject message){
        Future<JsonObject> reply = expectReply(message);
        send(message);
        return reply;
    }

    /**
     * Wait for OdoX's reply to a message sent later, e.g. after a pause. Gives the message an id if it has none.
     * @return completes with the reply.
     */
    protected Future<JsonObject> expectReply(JsonObject message){
        assignId(message);
        Promise<JsonObject> reply = Promise.promise();
        pendingReplies.put(message.getString("id"), reply);
        return reply.future();
    }

    /**
     * Fail the request with the given id, if it is still waiting for a reply, e.g. because OdoX could not deliver it.
     */
    public void failReply(String id, String reason){
        Promise<JsonObject> request = id == null? null : pendingReplies.remove(id);
        if(request != null){
            request.tryFail(reason);
        }
    }

    /**
     * Fail every request still waiting for a reply, e.g. when the task ends.
     */
    public void failPending(String reason){
        pendingReplies.values().forEach(request->request.tryFail(reason));
        pendingReplies.clear();
    }

    protected boolean send(String data){
        if(isOpen()){

            log.info("Message sent over websocket! {}", getClass().getName());
            connection.send(data);
            onSent(data);
            return true;
        }else{

            queue.add(data);
            log.info("added message to queue! Queue size: {} {}", queue.size(), getClass().getName());
            return false;
        }
    }

    /**
     * Called once a message has gone out over the websocket, including a queued message sent when the channel opens again.
     * @param data the message as sent.
     */
    protected void onSent(String data){
    }

    protected void addHistory(JsonObject data){
        data.put("timestamp", Instant.now().toString());
        history.add(data);
    }

    /**
     * Send a message on this channel. It is stamped with the protocol version and channel, and given an id if it has none.
     */
    protected boolean send(JsonObject data){
        assignId(data);
        data.put("v", WebSocketConnection.PROTOCOL_VERSION).put("channel", channel.name);
        log.info("Sending: \n{}",data.encodePrettily());
        return send(data.encode());
    }

    private static void assignId(JsonObject message){
        if(message.getString("id") == null){
            message.put("id", UUID.randomUUID().toString());
        }
    }


    protected JsonObject makeNotifyPathCompleteRequest(String source){
        JsonObject notifyPathCompleteRequest = new JsonObject()
                .put("type", "PATH_COMPLETE")
                .put("source", source)
                //resolve the paths request id, this can either be a guidance request (Request) or execution request (ExecutionRequest) TODO: refactor this
                .put("pathsRequestId", client.getRequestManager().getExecutionId().toString());

        return notifyPathCompleteRequest;
    }

    protected void saveHistory(String source){
        if(client.getRequestManager().getEvalId() != null){
            String fileName = "%s/%s-%s-history.json".formatted(client.getRequestManager().getExperimentFolderPath(), client.getRequestManager().getEvalId(),source).replaceAll("\\|","-");
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
