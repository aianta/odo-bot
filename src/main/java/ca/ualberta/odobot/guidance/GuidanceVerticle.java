package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.Localizer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.TrustOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GuidanceVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(GuidanceVerticle.class);
    private static final String SSL_FOLDER_PATH = "./ssl/"; //Path to folder containing the .jks
    private static final String JKS_NAME = "odobot-server.jks";
    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 7080;

    private HttpServer server;

    private Router mainRouter;

    private Router api;



    @Override
    public void start(Promise<Void> startPromise) throws Exception {

        log.info("Starting guidance verticle");

        JksOptions jksOptions = new JksOptions()
                .setPath(SSL_FOLDER_PATH+JKS_NAME)
                .setAlias("odobot-server")
                .setPassword(System.getenv("ODOBOT_JKS_PASSWORD"));


        HttpServerOptions options = new HttpServerOptions()
                .setLogActivity(true)
                .setHost(HOST)
                .setPort(PORT)
                .setSsl(true)
                .setKeyStoreOptions(jksOptions)
                .setMaxWebSocketFrameSize(10000000)  //10MB
                .setMaxWebSocketMessageSize(10000000)//10MB
                ;

        server = vertx.createHttpServer(options);

        server.webSocketHandler(WebSocketConnection::new);

        mainRouter = Router.router(vertx);
        api = Router.router(vertx);

        //Define API routes
        api.route().method(HttpMethod.GET).path("/targetNodes").handler(this::getTargetNodes);

        mainRouter.route().handler(LoggerHandler.create());
        mainRouter.route().handler(BodyHandler.create());
        mainRouter.route().handler(rc->{
            rc.response().putHeader("Access-Control-Allow-Origin", "*");
            rc.next();
        });
        mainRouter.route(API_PATH_PREFIX).subRouter(api);
        mainRouter.route().handler(rc->rc.response().setStatusCode(200).end("Greetings! This should be a secure line!"));

        server.requestHandler(mainRouter).listen(PORT);

        log.info("Guidance verticle started and server listening on port {}", PORT);

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
