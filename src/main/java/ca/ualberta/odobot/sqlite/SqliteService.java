package ca.ualberta.odobot.sqlite;

import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.sqlite.impl.SqliteServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;

@ProxyGen
public interface SqliteService {

    static SqliteService create(Vertx vertx){
        return new SqliteServiceImpl(vertx);
    }

    static SqliteService createProxy(Vertx vertx, String address){
        return new SqliteServiceVertxEBProxy(vertx, address);
    }

    Future<Void> saveTrainingMaterial(JsonObject json);

    Future<JsonArray> loadTrainingMaterialsForDataset(String dataset);

    Future<Set<String>> getHarvestProgress(String dataset);

    Future<Void> saveTrainingExemplar(JsonObject json);

    /**
     * Returns the list of {@link ca.ualberta.odobot.sqlite.impl.TrainingExemplar} in json form
     * that belong to the training dataset with the specified name.
     *
     * Dataset names should be defined during log preprocessing.
     * @param datasetName
     * @return
     */
    Future<JsonArray> loadTrainingDataset(String datasetName);

    Future<Void> insertLogEntry(JsonObject json);

    Future<JsonArray> selectLogs(long timestampMilli, long range);


}
