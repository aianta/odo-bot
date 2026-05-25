package ca.ualberta.odobot.modelconstruction.statelabeling.impl;

import ca.ualberta.odobot.guidance.RequestManager;
import ca.ualberta.odobot.modelconstruction.statelabeling.AIStrategy;
import ca.ualberta.odobot.modelconstruction.statelabeling.StateLabelingService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class StateLabelingServiceImpl implements StateLabelingService {

    private static final Logger log = LoggerFactory.getLogger(StateLabelingServiceImpl.class);

    private Vertx vertx;
    private JsonObject config;
    private AIStrategy strategy;

    public StateLabelingServiceImpl(Vertx vertx, JsonObject config) {
        this.vertx = vertx;
        this.config = config;

        OpenAIStrategy _strategy = new OpenAIStrategy(config);
        RequestManager.tokenUsageRecordListeners.add(_strategy::onNewTokenUsageRecord);

        this.strategy = _strategy;
    }

    @Override
    public Future<JsonObject> generateStateLabeling(String clusterId, List<JsonObject> snapshots) {
        return vertx.executeBlocking(()->{
            return strategy.generateStateLabeling(clusterId, snapshots);
        });
    }
}
