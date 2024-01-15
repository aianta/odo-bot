package ca.ualberta.odobot.tpg.service;

import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.service.impl.TPGServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface TPGService {

    static TPGService create(){ return new TPGServiceImpl(); }

    static TPGService createProxy(Vertx vertx, String address){
        return new TPGServiceVertxEBProxy(vertx,address);
    }

    /**
     *
     * @param config
     * @param dataset List of JsonObject representations of training exemplars
     * @return
     */
    Future<JsonObject> train(JsonObject config, JsonArray dataset);

    Future<Void> identify(JsonObject config, JsonObject exemplar);

}
