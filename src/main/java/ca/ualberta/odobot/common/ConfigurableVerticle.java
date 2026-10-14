package ca.ualberta.odobot.common;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.JsonArray;
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

            //Secrets (API keys, passwords) are masked: the log ends up in docker logs and run artifacts.
            masked(config).forEach(entry -> log.info("\t{}: {}", entry.getKey(), entry.getValue()));

            _config = config;  //Assign config to _config field making it visible to implementing subclasses.

             onStart();
        });

        return Completable.complete();
    }

    /**
     * @return a copy of the config in which the value of every key that looks secret (key, secret, password, token),
     * at any depth, is replaced by "***".
     */
    static JsonObject masked(JsonObject config){
        JsonObject copy = new JsonObject();
        config.forEach(entry -> copy.put(entry.getKey(), maskValue(entry.getKey(), entry.getValue())));
        return copy;
    }

    private static Object maskValue(String key, Object value){
        if(value instanceof JsonObject json){
            return masked(json);
        }
        if(value instanceof JsonArray array){
            JsonArray copy = new JsonArray();
            array.forEach(item -> copy.add(maskValue("", item)));
            return copy;
        }
        String k = key.toLowerCase();
        boolean secret = k.contains("secret") || k.contains("password") || k.contains("token") || k.endsWith("key")
                || k.contains("api_key");
        return secret && value != null? "***" : value;
    }

    public abstract String configFilePath();

    public abstract String serviceName();

    public abstract Completable onStart();
}
