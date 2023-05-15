package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.extractors.impl.*;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.logpreprocessor.xes.XesTransformer;
import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.model.*;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Promise;
import io.vertx.rxjava3.core.Vertx;

import org.deckfour.xes.model.XLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;


import static ca.ualberta.odobot.logpreprocessor.Constants.*;


public class SimplePreprocessingPipeline extends AbstractPreprocessingPipeline implements PreprocessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(SimplePreprocessingPipeline.class);

    public SimplePreprocessingPipeline(Vertx vertx, UUID id, String slug, String name){
        super(vertx, slug);
        setId(id);
        setName(name);
    }

    @Override
    public Future<List<Timeline>> makeTimelines(Map<String, List<JsonObject>> eventsMap) {
        Promise<List<Timeline>> promise = Promise.promise();
        List<Timeline> results = new ArrayList<>();

        SemanticSequencer sequencer = new SemanticSequencer();

        eventsMap.forEach(
                (index, events)->{

                    //Timeline data structure construction
                    Timeline timeline = sequencer.parse(events);
                    timeline.getAnnotations().put("source-index", index);

                    //Semantic artifact extraction config
                    Multimap<Class,SemanticArtifactExtractor> extractorMultimap = ArrayListMultimap.create();
                    extractorMultimap.put(ClickEvent.class, new SimpleClickEventTermsExtractor());
                    extractorMultimap.put(ClickEvent.class, new SimpleClickEventIdTermsExtractor());
                    extractorMultimap.put(ClickEvent.class, new SimpleClickEventCssClassTermsExtractor());
                    extractorMultimap.put(Effect.class, new SimpleEffectTermsExtractor());
                    extractorMultimap.put(Effect.class, new SimpleEffectCssClassTermsExtractor());
                    extractorMultimap.put(Effect.class, new SimpleEffectIdTermsExtractor());
                    extractorMultimap.put(DataEntry.class, new SimpleDataEntryTermsExtractor());
                    extractorMultimap.put(DataEntry.class, new SimpleDataEntryCssClassTermsExtractor());
                    extractorMultimap.put(DataEntry.class, new SimpleDataEntryIdTermsExtractor());


                    /**
                     * Semantic artifact extraction:
                     *
                     * Go through each timeline entity and get all matching extractors for that entity's class.
                     * Then apply each extractor to the entity and add it's output to the entity's semantic artifacts.
                     */
                    timeline.forEach(entity -> {
                        Collection<SemanticArtifactExtractor> entityExtractors = extractorMultimap.get(entity.getClass());
                        entityExtractors.forEach(extractor->
                                entity.getSemanticArtifacts().put(extractor.artifactName(), extractor.extract(entity)));

                    });

                    results.add(timeline);

                }
        );

        promise.complete(results);

        return promise.future();
    }

    @Override
    public Future<List<TimelineEntity>> makeEntities(List<Timeline> timelines) {
        Promise<List<TimelineEntity>> promise = Promise.promise();

        List<TimelineEntity> result = new ArrayList<>();
        timelines.forEach(timeline -> result.addAll(timeline));
        promise.complete(result);

        return promise.future();
    }

    @Override
    public Future<JsonObject> makeActivityLabels(List<TimelineEntity> entities) {
        Promise<JsonObject> promise = Promise.promise();
        JsonArray entitiesJson = entities.stream().map(entity -> entity.toJson()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        JsonObject requestObject = new JsonObject()
                .put("id", "some-id").put("entities", entitiesJson);

        client.post(DEEP_SERVICE_PORT, DEEP_SERVICE_HOST,DEEP_SERVICE_ACTIVITY_LABELS_ENDPOINT)
                .rxSendJsonObject(requestObject)
                .doOnError(super::genericErrorHandler)
                .subscribe(response->{
                   promise.complete(response.bodyAsJsonObject());
                });

        return promise.future();
    }

    @Override
    public Future<File> makeXes(JsonArray timelines, JsonObject activityLabels) {
        Promise<File> promise = Promise.promise();
        XesTransformer transformer = new XesTransformer();
        XLog xesLog = transformer.parse(timelines, activityLabels);
        File out = new File("log.xml");
        transformer.save(xesLog, out);
        promise.complete(out);
        return promise.future();
    }

}
