package ca.ualberta.odobot.common;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base verticle configurable with yaml that starts an HttpServer for exposing api endpoints.
 */
public abstract class HttpServiceVerticle extends ConfigurableVerticle {

    private static final Logger log = LoggerFactory.getLogger(HttpServiceVerticle.class);

    protected HttpServer server;

    protected Router mainRouter;
    protected Router api;

    public abstract String serviceName();

    public abstract String configFilePath();

    public Completable onStart(){

        //Initialize Http Server
        HttpServerOptions serverOptions = new HttpServerOptions()
                .setHost(_config.getString("host", "0.0.0.0"))
                .setPort(_config.getInteger("port", 8080));

        server = vertx.createHttpServer(serverOptions);
        mainRouter = Router.router(vertx);
        api = Router.router(vertx);

        mainRouter.route().handler(LoggerHandler.create());
        mainRouter.route().handler(BodyHandler.create().setBodyLimit(1048576000)); //Let's set it to ~1GB
        mainRouter.route().handler(rc->{ //Configure permissive CORS
            rc.response().putHeader("Access-Control-Allow-Origin", "*");
            rc.next();
        });

        mainRouter.route(_config.getString("apiPathPrefix", "/api/*")).subRouter(api);
        server.requestHandler(mainRouter).listen(_config.getInteger("port", 8080));

        log.info("{} service started on port: {}", serviceName(), _config.getInteger("port", 8080));

        return Completable.complete();
    }


}
