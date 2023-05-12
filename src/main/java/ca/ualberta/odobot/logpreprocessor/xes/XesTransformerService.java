package ca.ualberta.odobot.logpreprocessor.xes;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Vertx;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ProxyGen
public interface XesTransformerService {


    static XesTransformerService create(Vertx vertx){return null;}

    static XesTransformerService createProxy(Vertx vertx, String address){
        return null;
    }
}
