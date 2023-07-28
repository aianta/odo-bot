package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import io.vertx.core.Future;

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


    public Future<JsonObject> makeActivityLabels(List<JsonObject> entities){
        log.info("Making activity labels with enhanced embeddings deep service endpoint");
        return callActivityLabelEndpoint(DEEP_SERVICE_ACTIVITY_LABELS_V2_ENDPOINT, entities);

    }
}
