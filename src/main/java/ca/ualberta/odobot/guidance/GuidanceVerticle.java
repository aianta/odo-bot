package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;

import io.vertx.rxjava3.ext.web.RoutingContext;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.Map;
import java.util.UUID;


public class GuidanceVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(GuidanceVerticle.class);

    private static final int PERIODIC_REPORTING_INTERVAL = 10000; //10s


    public static Vertx _vertx;

    public String serviceName(){
        return "Guidance Service";
    }

    public String configFilePath(){
        return "config/guidance.yaml";
    }

    protected HttpServerOptions getServerOptions() {
        JksOptions jksOptions = new JksOptions()
                .setPath(_config.getString("jksPath"))
                .setAlias("odobot-server")
                .setPassword(_config.getString("jksPassword"));

        HttpServerOptions serverOptions = super.getServerOptions();
        serverOptions.setLogActivity(true)
                .setSsl(true)
                .setKeyStoreOptions(jksOptions)
                .setMaxWebSocketFrameSize(10000000)     //10MB
                .setMaxWebSocketMessageSize(10000000)   //10MB
                ;

        return serverOptions;
    }

    protected io.vertx.rxjava3.core.http.HttpServer afterServerCreate(io.vertx.rxjava3.core.http.HttpServer server) {
        ClientRegistry.setStrict(_config.getBoolean("strictClientIds", false));

        /*
         * OdoX puts its clientId and the socket's source in the URL, e.g. wss://host/?clientId=<uuid>&source=ControlSocket,
         * so the socket is bound to its client here, before any message. In strict mode sockets of clients no task expects
         * are refused. Older OdoX sends neither; its sockets are bound by their first message, see WebSocketConnection#onMessage.
         */
        server.webSocketHandler(serverSocket->{
            ServerWebSocket socket = serverSocket.getDelegate();
            UUID clientId = WebSocketConnection.clientIdFromUri(socket.uri());
            Source source = WebSocketConnection.sourceFromUri(socket.uri());

            if(clientId != null && ClientRegistry.isStrict() && !ClientRegistry.isExpected(clientId)){
                log.warn("Rejecting websocket of OdoX client {} ({}), which no task expects.", clientId, socket.remoteAddress());
                socket.reject(403);
                return;
            }

            new WebSocketConnection(vertx.getDelegate(), socket, clientId, source);
        });
        return server;
    }

    @Override
    public Completable onStart()  {
        super.onStart();

        _vertx = vertx.getDelegate();

//        server.webSocketHandler(serverSocket->new WebSocketConnection(_vertx, serverSocket.getDelegate()));

        //Define API routes
        api.route().method(HttpMethod.GET).path("/targetNodes").handler(this::getTargetNodes);
        //api.route().method(HttpMethod.POST).path("/evaluate").handler(this::evaluationHandler);

        mainRouter.route().handler(rc->rc.response().setStatusCode(200).end("Greetings! This should be a secure line!"));


//        vertx.setPeriodic(PERIODIC_REPORTING_INTERVAL, interval->{
//           ClientRegistry.printClients();
//        });

        return Completable.complete();
    }


    public void getTargetNodes(RoutingContext rc){

        JsonArray targetNodes = new JsonArray();

        GraphDatabaseService db = LogPreprocessor.graphDB.db;
        try(Transaction tx = db.beginTx();
            Result result = tx.execute("MATCH (n:APINode) return n.method, n.path, n.id;")
        ){
            while (result.hasNext()){
                JsonObject targetNode = new JsonObject();
                Map<String, Object> row = result.next();
                for(Map.Entry<String,Object> column: row.entrySet()){
                    if(column.getKey().equals("n.id")){
                        targetNode.put("id", (String)column.getValue());
                    }
                    if(column.getKey().equals("n.path")){
                        targetNode.put("path", (String)column.getValue());
                    }
                    if(column.getKey().equals("n.method")){
                        targetNode.put("method", (String)column.getValue());
                    }
                }

                targetNodes.add(targetNode);
            }
        }

        rc.response().setStatusCode(200).end(targetNodes.encode());

    }

}
