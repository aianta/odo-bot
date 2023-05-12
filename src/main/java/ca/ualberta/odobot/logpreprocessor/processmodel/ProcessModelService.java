package ca.ualberta.odobot.logpreprocessor.processmodel;


import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Vertx;

@ProxyGen
public interface ProcessModelService {

    static ProcessModelService create(Vertx vertx){
        return null;
    }

    static ProcessModelService createProxy(Vertx vertx, String address){
        return null;
    }

}
