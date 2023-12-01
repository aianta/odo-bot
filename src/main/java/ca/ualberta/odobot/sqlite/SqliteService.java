package ca.ualberta.odobot.sqlite;

import ca.ualberta.odobot.sqlite.impl.SqliteServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Vertx;

@ProxyGen
public interface SqliteService {

    static SqliteService create(Vertx vertx){
        return new SqliteServiceImpl();
    }

    static SqliteService createProxy(Vertx vertx, String address){
        return new SqliteServiceVertxEBProxy(vertx, address);
    }


}
