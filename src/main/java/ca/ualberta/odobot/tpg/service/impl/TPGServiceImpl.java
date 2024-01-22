package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.service.TPGService;
import ca.ualberta.odobot.tpg.teams.Team;
import ca.ualberta.odobot.tpg.util.SaveLoad;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;

public class TPGServiceImpl implements TPGService {

    private static final Logger log = LoggerFactory.getLogger(TPGServiceImpl.class);

    private SaveLoad saveLoad;

    private ElasticsearchService elasticsearchService;

    public TPGServiceImpl(ElasticsearchService elasticsearchService){
        this.elasticsearchService = elasticsearchService;
        this.saveLoad = new SaveLoad();

    }

    @Override
    public Future<JsonObject> train(JsonObject config, JsonArray data) {
        Promise<TPGAlgorithm> promise = Promise.promise();
        List<TrainingExemplar> dataset = data.stream().map(o->TrainingExemplar.fromJson((JsonObject)o)).collect(Collectors.toList());

        log.info("Starting training task on a new thread");
        /**
         * For more details about using a callable with the Thread class see:
         * https://stackoverflow.com/questions/25231149/can-i-use-callable-threads-without-executorservice
         */
        TrainingTask task = new TrainingTask( promise, config, elasticsearchService, dataset);
        Thread thread = new Thread(task);
        thread.start();

        return promise.future().compose(this::saveChampion);
    }

    @Override
    public Future<Void> identify(JsonObject config, JsonObject exemplarJson) {
        TrainingExemplar exemplar = TrainingExemplar.fromJson(exemplarJson);

        String championPath = config.getString("championPath");
        try{
            Team champion  = saveLoad.loadTeam(championPath);

            double [] registerArray = champion.getAction(new HashSet<>(), exemplar.featureVector());

            log.info("Champion produced register array: {}", registerArray);
        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
            return Future.failedFuture(ioe);
        }


        return Future.succeededFuture();
    }

    private Future<JsonObject> saveChampion(TPGAlgorithm trainedTPG){

        try{


        TPGLearn tpgLearn = trainedTPG.getTPGLearn();
        Team champion = tpgLearn.getRootTeams().get(0);

        String championPath = saveLoad.saveTeam(champion, tpgLearn.getEpochs(), 0, "./tpg/champions/");


        return Future.succeededFuture(new JsonObject()
                .put("championPath", championPath)
        );
        }catch (Exception e){
            log.error(e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }


}
