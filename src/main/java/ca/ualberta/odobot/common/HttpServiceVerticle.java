package ca.ualberta.odobot.common;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.config.ConfigRetriever;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base verticle configurable with yaml that starts an HttpServer for exposing
 * api endpoints.
 */
public abstract class HttpServiceVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(HttpServiceVerticle.class);

    protected JsonObject _config;

    private ConfigRetriever retriever;

    private ConfigStoreOptions configStoreOptions = new ConfigStoreOptions()
            .setType("file")
            .setFormat("yaml");

    protected HttpServer server;

    protected Router mainRouter;
    protected Router api;



    public Completable rxStart(){

        configStoreOptions.setConfig(
                new JsonObject()
                        .put("path", configFilePath())
        );

        retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(configStoreOptions));

        retriever.getConfig().subscribe(config->{
            log.info("{} configuration loaded from: {}", serviceName(), configFilePath());

            config.forEach(entry->log.info("\t{}: {}", entry.getKey(), entry.getValue()));

            //Initalize Http Server
            HttpServerOptions serverOptions = new HttpServerOptions()
                    .setHost(config.getString("host", "0.0.0.0"))
                    .setPort(config.getInteger("port", 8080));

            server = vertx.createHttpServer(serverOptions);
            mainRouter = Router.router(vertx);
            api = Router.router(vertx);

            mainRouter.route().handler(LoggerHandler.create());
            mainRouter.route().handler(BodyHandler.create());
            mainRouter.route().handler(rc->{ //Configure permissive CORS
                rc.response().putHeader("Access-Control-Allow-Origin", "*");
                rc.next();
            });

            mainRouter.route(config.getString("apiPathPrefix", "/api/*")).subRouter(api);
            server.requestHandler(mainRouter).listen(config.getInteger("port", 8080));

            log.info("{} service started on port: {}", serviceName(), config.getInteger("port", 8080));

            _config = config; //Assign config to _config field making it visible to implementing subclasses.
        });



        return super.rxStart();
    }

    public abstract String serviceName();

    public abstract String configFilePath();


}
