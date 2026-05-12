package ca.ualberta.odobot.sqlite;

import ca.ualberta.odobot.sqlite.impl.SqliteServiceImpl;
import ca.ualberta.odobot.sqlite.impl.SqliteVectorServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface SqliteVectorService {

    static SqliteVectorService create(JsonObject config){
        return new SqliteVectorServiceImpl(config);
    }

    static SqliteVectorService createProxy(Vertx vertx, String address){
        return new SqliteVectorServiceVertxEBProxy(vertx, address);
    }

    Future<Void> readyVectorsForQuerying();

    Future<List<String>> topK(int k, String queryString);

    Future<Void> embedSyntheticTasks(JsonObject tasks);

    Future<Void> embedSyntheticTask(String trajectoryId, String task);
}
