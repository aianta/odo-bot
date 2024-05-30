package ca.ualberta.odobot.guidance;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.TrustOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuidanceVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(GuidanceVerticle.class);
    private static final String SSL_FOLDER_PATH = "./ssl/"; //Path to folder containing the .jks
    private static final String JKS_NAME = "odobot-server.jks";
    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 7080;

    private HttpServer server;

    private Router mainRouter;

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
                ;

        server = vertx.createHttpServer(options);

        server.webSocketHandler(this::handleWebsocketConnection);

        mainRouter = Router.router(vertx);
        mainRouter.route().handler(LoggerHandler.create());
        mainRouter.route().handler(BodyHandler.create());
        mainRouter.route().handler(rc->{
            rc.response().putHeader("Access-Control-Allow-Origin", "*");
            rc.next();
        });
        mainRouter.route().handler(rc->rc.response().setStatusCode(200).end("Greetings! This should be a secure line!"));

        server.requestHandler(mainRouter).listen(PORT);

        log.info("Guidance verticle started and server listening on port {}", PORT);

    }

    private void handleWebsocketConnection(ServerWebSocket serverWebSocket) {


    }

}
