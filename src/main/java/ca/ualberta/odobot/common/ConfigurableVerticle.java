package ca.ualberta.odobot.common;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.config.ConfigRetriever;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base verticle configurable with yaml.
 */
public abstract class ConfigurableVerticle  extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableVerticle.class);

    protected JsonObject _config;

    private ConfigRetriever retriever;

    private ConfigStoreOptions configStoreOptions = new ConfigStoreOptions()
            .setType("file")
            .setFormat("yaml");

    public Completable rxStart(){
        configStoreOptions.setConfig(
                new JsonObject()
                        .put("path", configFilePath())
        );

        retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(configStoreOptions));



        retriever.getConfig().subscribe(config-> {
            log.info("{} configuration loaded from: {}", serviceName(), configFilePath());

            config.forEach(entry -> log.info("\t{}: {}", entry.getKey(), entry.getValue()));

            _config = config;  //Assign config to _config field making it visible to implementing subclasses.

             onStart();
        });

        return Completable.complete();
    }

    public abstract String configFilePath();

    public abstract String serviceName();

    public abstract Completable onStart();
}
