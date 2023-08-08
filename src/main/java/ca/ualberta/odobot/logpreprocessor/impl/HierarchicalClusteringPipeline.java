package ca.ualberta.odobot.logpreprocessor.impl;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static ca.ualberta.odobot.logpreprocessor.Constants.DEEP_SERVICE_ACTIVITY_LABELS_V4_ENDPOINT;

public class HierarchicalClusteringPipeline extends EffectOverhaulPipeline{
    private static final Logger log = LoggerFactory.getLogger(HierarchicalClusteringPipeline.class);

    public HierarchicalClusteringPipeline(Vertx vertx, UUID id, String slug, String name) {
        super(vertx, id, slug, name);
    }

    public Future<JsonObject> makeActivityLabels(List<JsonObject> entities){
        log.info("Making activity labels with hierarchical clustering deep service endpoint");
        return callActivityLabelEndpoint(DEEP_SERVICE_ACTIVITY_LABELS_V4_ENDPOINT, entities);
    }

}
