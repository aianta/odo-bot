package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.extractors.impl.*;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.logpreprocessor.xes.XesTransformer;
import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.model.*;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Promise;
import io.vertx.rxjava3.core.Vertx;

import io.vertx.rxjava3.core.buffer.Buffer;

import org.deckfour.xes.model.XLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;


import static ca.ualberta.odobot.logpreprocessor.Constants.*;


public class SimplePreprocessingPipeline extends AbstractPreprocessingPipeline implements PreprocessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(SimplePreprocessingPipeline.class);
    protected Multimap<Class, SemanticArtifactExtractor> extractorMultimap = null;

    public SimplePreprocessingPipeline(Vertx vertx, UUID id, String slug, String name){
        super(vertx, slug);
        setId(id);
        setName(name);

        EffectExtractor effectCssTermsExtractor = new EffectExtractor("cssClassTerms", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_CSS_CLASSES);
        EffectExtractor effectIdTermsExtractor = new EffectExtractor("idTerms", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_ID);

        //Semantic artifact extraction config
        extractorMultimap = ArrayListMultimap.create();
        extractorMultimap.put(ClickEvent.class, new SimpleClickEventTermsExtractor());
        extractorMultimap.put(ClickEvent.class, new SimpleClickEventIdTermsExtractor());
        extractorMultimap.put(ClickEvent.class, new SimpleClickEventCssClassTermsExtractor());
        extractorMultimap.put(ClickEvent.class, new ClickEventBaseURIExtractor());
        extractorMultimap.put(ClickEvent.class, new NextIdExtractor());
        extractorMultimap.put(Effect.class, new NoZeroTermsEffectExtractor());
        extractorMultimap.put(Effect.class, effectCssTermsExtractor);
        extractorMultimap.put(Effect.class, effectIdTermsExtractor);
        extractorMultimap.put(Effect.class, new EffectBaseURIExtractor());
        extractorMultimap.put(Effect.class, new NextIdExtractor());
        extractorMultimap.put(DataEntry.class, new SimpleDataEntryTermsExtractor());
        extractorMultimap.put(DataEntry.class, new SimpleDataEntryCssClassTermsExtractor());
        extractorMultimap.put(DataEntry.class, new SimpleDataEntryIdTermsExtractor());
        extractorMultimap.put(DataEntry.class, new LocalizedDataEntryTermsExtractor());
        extractorMultimap.put(DataEntry.class, new DataEntryBaseURIExtractor());
        extractorMultimap.put(DataEntry.class, new NextIdExtractor());

    }

    public JsonObject toJson(){
        JsonObject result = super.toJson();

        result.put("class", getClass().getName());

        JsonObject extractionConfig = new JsonObject();
        extractorMultimap.forEach((timelineEntityClass, extractor)->{
            extractionConfig.put(timelineEntityClass.getName(), extractionConfig.getJsonArray(timelineEntityClass.getName(), new JsonArray()).add(extractor.getClass().getName()));
        });

        result.put("extractionConfig", extractionConfig);

        return result;
    }

    @Override
    public Future<List<Timeline>> makeTimelines(Map<String, List<JsonObject>> eventsMap) {
        Promise<List<Timeline>> promise = Promise.promise();
        List<Timeline> results = new ArrayList<>();

        SemanticSequencer sequencer = new SemanticSequencer();

        eventsMap.forEach(
                (index, events)->{

                    try{
                        //Timeline data structure construction
                        Timeline timeline = sequencer.parse(events);
                        timeline.getAnnotations().put("source-index", index);


                        /**
                         * Semantic artifact extraction:
                         *
                         * Go through each timeline entity and get all matching extractors for that entity's class.
                         * Then apply each extractor to the entity and add it's output to the entity's semantic artifacts.
                         */
                        ListIterator<TimelineEntity> it = timeline.listIterator();
                        while (it.hasNext()){
                            TimelineEntity entity = it.next();
                            Collection<SemanticArtifactExtractor> entityExtractors = extractorMultimap.get(entity.getClass());
                            entityExtractors.forEach(extractor->
                                    entity.getSemanticArtifacts().put(extractor.artifactName(), extractor.extract(entity,it.previousIndex(),timeline)));
                        }

                        results.add(timeline);
                    }catch (Exception e){
                        log.error(e.getMessage(), e);
                        promise.fail(e);
                    }


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
    public Future<JsonObject> makeActivityLabels(List<JsonObject> entities) {
        log.info("Making activity labels with activity labels endpoint v1");
        return callActivityLabelEndpoint(DEEP_SERVICE_ACTIVITY_LABELS_ENDPOINT, entities);
    }

    @Override
    public Future<File> makeXes(JsonArray timelines, JsonObject activityLabels) {
        log.info("Making XES file.");
        log.info("{}", timelines.encodePrettily());
        Promise<File> promise = Promise.promise();
        XesTransformer transformer = new XesTransformer();
        XLog xesLog = transformer.parse(timelines, activityLabels);


        File out = new File(XES_FILE_NAME);
        transformer.save(xesLog, out);
        promise.complete(out);
        return promise.future();
    }

    @Override
    public Future<Map<String,Buffer>> makeModelVisualization(File xes) {
        log.info("Making visualization");
        Promise<Map<String,Buffer>> promise = Promise.promise();
        vertx.fileSystem().rxReadFile(xes.toPath().toString())
                .doOnError(err->log.error(err.getMessage(),err))
                .subscribe(buffer->{
            client.post(DEEP_SERVICE_PORT, DEEP_SERVICE_HOST, DEEP_SERVICE_MODEL_ENDPOINT).rxSendBuffer(buffer)
                    .doOnSuccess(response->{
                        try{
                            Map<String,Buffer> results = new HashMap<>();
                            JsonObject modelVisualization = response.bodyAsJsonObject();
                            log.info("model visualization response:");
                            log.info("{}", modelVisualization.encodePrettily());
                            if (modelVisualization.containsKey(BPMN_KEY)){
                                results.put(BPMN_KEY, Buffer.buffer(Base64.getDecoder().decode(modelVisualization.getString(BPMN_KEY))));
                            }
                            results.put(TREE_KEY, Buffer.buffer(Base64.getDecoder().decode(modelVisualization.getString(TREE_KEY))));
                            results.put(DFG_KEY, Buffer.buffer(Base64.getDecoder().decode(modelVisualization.getString(DFG_KEY))));
                            results.put(PETRI_KEY, Buffer.buffer(Base64.getDecoder().decode(modelVisualization.getString(PETRI_KEY))));
                            results.put(TRANSITION_KEY, Buffer.buffer(Base64.getDecoder().decode(modelVisualization.getString(TRANSITION_KEY))));

                            promise.complete(results);
                        }catch (Exception e){
                            log.error(e.getMessage(), e);
                            promise.fail(e);
                        }

                    })
                    .doOnError(err->promise.fail(err))
                    .subscribe();

        });
        return promise.future();
    }


}
