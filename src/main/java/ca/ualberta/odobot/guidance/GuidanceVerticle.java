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
        server.webSocketHandler(serverSocket->new WebSocketConnection(vertx.getDelegate(), serverSocket.getDelegate()));
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
//           log.info("{} registered clients, generating status report!", WebSocketConnection.clientMap.size());
//           WebSocketConnection.clientMap.values().forEach(client->{
//               log.info("{}", client.statusReport().encodePrettily());
//           });
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
