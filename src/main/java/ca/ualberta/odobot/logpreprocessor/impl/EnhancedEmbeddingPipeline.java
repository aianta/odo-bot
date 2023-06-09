package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class EnhancedEmbeddingPipeline extends SimplePreprocessingPipeline implements PreprocessingPipeline {
    private static final Logger log = LoggerFactory.getLogger(EnhancedEmbeddingPipeline.class);

    public EnhancedEmbeddingPipeline(Vertx vertx, UUID id, String slug, String name) {
        super(vertx, id, slug, name);
    }

    /**
     * TODO: Extract this logic out into the abstract class maybe? Let the two implementing
     * classes reuse it whenever a different activity labels step is merely calling a different
     * deep service endpoint.
     *
     * @param entities
     * @return
     */
    public Future<JsonObject> makeActivityLabels(List<JsonObject> entities){
        log.info("Making activity labels with enhanced embeddings deep service endpoint");
        Promise<JsonObject> promise = Promise.promise();

        JsonArray entitiesJson = entities.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        JsonObject requestObject = new JsonObject()
                .put("id", UUID.randomUUID().toString()).put("entities", entitiesJson);

        log.info("requestObject: {}", requestObject.encodePrettily());

        client.post(DEEP_SERVICE_PORT, DEEP_SERVICE_HOST, DEEP_SERVICE_ACTIVITY_LABELS_V2_ENDPOINT )
                .rxSendJsonObject(requestObject)
                .doOnError(err->{
                    promise.fail(err);
                    super.genericErrorHandler(err);
                }).subscribe(response->{
                    JsonObject data = response.bodyAsJsonObject();
                    log.info("{}", data.encodePrettily());
                    promise.complete(data);
                });

        return promise.future();

    }
}
