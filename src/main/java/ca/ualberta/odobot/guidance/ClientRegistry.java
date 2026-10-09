package ca.ualberta.odobot.guidance;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Associates the {@link WebSocketConnection}s of OdoX instances with the task whose browser opened them.
 *
 * OdoX identifies itself with a clientId, the UUID of its extension instance. {@code EvaluateTask} chooses that UUID
 * when it creates the browser, so it calls {@link #expect} with it before the browser starts. Sockets that arrive with
 * that clientId are bound to the task's {@link OdoClient}, and the task gets the client once the sockets it needs are
 * connected.
 *
 * Sockets with a clientId no task expects are kept on a client of their own, which no task is ever given. In strict
 * mode they are refused instead.
 */
public final class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    record Expectation(UUID id, JsonObject taskContext, Promise<OdoClient> ready){}

    private static final Map<UUID, OdoClient> clients = new ConcurrentHashMap<>();
    private static final Map<UUID, Expectation> expected = new ConcurrentHashMap<>();

    /**
     * Whether sockets with a clientId no task expects are refused. Set from {@code strictClientIds} in guidance.yaml.
     */
    static volatile boolean strict = false;

    private ClientRegistry(){}

    /**
     * Expect the OdoX instance with the given clientId, for the given task. Any previous client or expectation for the
     * id is released first.
     *
     * @param taskContext describes the task, e.g. its evalId, for the client's logs and status reports.
     * @return completes with the task's client once its control and event sockets are connected.
     */
    public static synchronized Future<OdoClient> expect(UUID id, JsonObject taskContext){
        release(id);
        Expectation expectation = new Expectation(id, taskContext, Promise.promise());
        expected.put(id, expectation);
        log.info("Expecting OdoX client {} for task {}", id, taskContext.encode());
        return expectation.ready().future();
    }

    public static boolean isExpected(UUID id){
        return id != null && expected.containsKey(id);
    }

    public static boolean isStrict(){
        return strict;
    }

    public static void setStrict(boolean strict){
        ClientRegistry.strict = strict;
    }

    /**
     * Bind a socket to the client with the given clientId, creating the client if this is its first socket.
     *
     * @return the client the socket is now bound to.
     */
    public static synchronized OdoClient bind(UUID id, Source source, WebSocketConnection connection){
        Expectation expectation = expected.get(id);

        OdoClient client = clients.computeIfAbsent(id, clientId->{
            if(expectation != null){
                log.info("Registering OdoX client {} for task {}", clientId, expectation.taskContext().encode());
            }else{
                log.warn("Registering OdoX client {}, which no task expects. No task will be given this client.", clientId);
            }
            return new OdoClient(clientId, expectation == null? null : expectation.taskContext());
        });

        switch (source){
            case EVENT_SOCKET -> client.setEvent(connection);
            case CONTROL_SOCKET -> client.setControl(connection);
            case GUIDANCE_SOCKET -> client.setGuidance(connection);
        }
        log.info("[{}][{}] Bound websocket", client.label(), source.name);

        //The task sends on the control and event sockets. The guidance socket reconnects with every page load, so it is
        //not waited for.
        if(expectation != null && client.getControl().isConnected() && client.getEvent().isConnected()
                && expectation.ready().tryComplete(client)){
            log.info("[{}] OdoX client ready", client.label());
        }

        return client;
    }

    /**
     * Close the sockets of the client with the given clientId and forget it, failing its expectation if it is still
     * pending. Other clients are not affected.
     */
    public static synchronized void release(UUID id){
        Expectation expectation = expected.remove(id);
        if(expectation != null){
            expectation.ready().tryFail("OdoX client %s was released before it connected".formatted(id));
        }

        OdoClient client = clients.remove(id);
        if(client != null){
            log.info("[{}] Releasing OdoX client", client.label());
            client.getControl().close();
            client.getEvent().close();
            client.getGuidance().close();
        }
    }

    /**
     * @return the client with the given clientId, or null if it has no sockets bound.
     */
    public static OdoClient get(UUID id){
        return clients.get(id);
    }

    public static void printClients(){
        log.info("OdoX clients [size: {}]", clients.size());
        clients.values().forEach(client->log.info("{}", client.statusReport().encode()));
    }
}
