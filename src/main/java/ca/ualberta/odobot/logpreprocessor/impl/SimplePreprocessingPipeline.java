package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.extractors.impl.*;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.logpreprocessor.xes.XesTransformer;
import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.sqlite.impl.DbLogEntry;
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
import java.util.stream.Collectors;


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
        EffectExtractor effectTagExtractor = new EffectExtractor("tags", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_TAG);



        //Semantic artifact extraction config
        extractorMultimap = ArrayListMultimap.create();
        extractorMultimap.put(ClickEvent.class, new SimpleClickEventTermsExtractor());
        extractorMultimap.put(ClickEvent.class, new SimpleClickEventIdTermsExtractor());
        extractorMultimap.put(ClickEvent.class, new SimpleClickEventCssClassTermsExtractor());
        extractorMultimap.put(ClickEvent.class, new NumericFreeExtractor(new ClickEventBaseURIExtractor()));
        extractorMultimap.put(ClickEvent.class, new NextIdExtractor());
        extractorMultimap.put(Effect.class, new NoZeroTermsEffectExtractor());
        extractorMultimap.put(Effect.class, effectCssTermsExtractor);
        extractorMultimap.put(Effect.class, effectIdTermsExtractor);
        extractorMultimap.put(Effect.class, effectTagExtractor);
        extractorMultimap.put(Effect.class, new NumericFreeExtractor(new EffectBaseURIExtractor()));
        extractorMultimap.put(Effect.class, new NextIdExtractor());
        extractorMultimap.put(DataEntry.class, new SimpleDataEntryTermsExtractor());
        extractorMultimap.put(DataEntry.class, new SimpleDataEntryCssClassTermsExtractor());
        extractorMultimap.put(DataEntry.class, new SimpleDataEntryIdTermsExtractor());
        extractorMultimap.put(DataEntry.class, new LocalizedDataEntryTermsExtractor());
        extractorMultimap.put(DataEntry.class, new NumericFreeExtractor(new DataEntryBaseURIExtractor()));
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

    public Future<Timeline> makeTimeline(String sourceIndex, List<JsonObject> events){

        SemanticSequencer sequencer = new SemanticSequencer();
        sequencer.setOnArtifact(artifact -> {
            if(artifact.getDomSnapshot() != null){
                domSequencingService.process(artifact.getDomSnapshot().outerHtml());
            }
        });

        sequencer.setDatabaseLogsSeekerFunction((timestamp, range)->sqliteService.selectLogs(timestamp, range).compose(
                jsonArray->Future.succeededFuture(jsonArray.stream()
                        .map(o->(JsonObject)o)
                        .map(DbLogEntry::fromJson)
                        .collect(Collectors.toList()))
        ));

        //Timeline data structure construction
        Timeline timeline = sequencer.parse(events);
        timeline.getAnnotations().put("source-index", sourceIndex);


        /**
         * Semantic artifact extraction:
         *
         * Go through each timeline entity and get all matching extractors for that entity's class.
         * Then apply each extractor to the entity and add it's output to the entity's semantic artifacts.
         */
        ListIterator<TimelineEntity> it = timeline.listIterator();
        while (it.hasNext()){
            try{
                TimelineEntity entity = it.next();
                Collection<SemanticArtifactExtractor> entityExtractors = extractorMultimap.get(entity.getClass());
                entityExtractors.forEach(extractor->
                        entity.getSemanticArtifacts().put(extractor.artifactName(), extractor.extract(entity,it.previousIndex(),timeline)));

                /**
                 * Need to prevent giving deep-service entities with no embeddable semantic artifacts.
                 * IE: one of the term lists below has to have a non-empty list of terms.
                 */
                Set<String> mustHaveOne = Set.of("idTerms", "cssClassTerms", "terms" ,"terms_added", "terms_removed", "cssClassTerms_added", "cssClassTerms_removed", "idTerms_added", "idTerms_removed");
                Iterator<Map.Entry<String,Object>> semanticArtifactsIterator = entity.getSemanticArtifacts().iterator();
                while (semanticArtifactsIterator.hasNext()){
                    Map.Entry<String,Object> entry = semanticArtifactsIterator.next();
                    if (mustHaveOne.contains(entry.getKey())){
                        JsonArray value = entity.getSemanticArtifacts().getJsonArray(entry.getKey());
                        if (!value.isEmpty()){
                            break;
                        }
                    }
                    //If we got to the end of the artifacts for this entity, and none of the mustHaveOne artifacts were found with values in their json arrays, remove the entity.
                    if(!semanticArtifactsIterator.hasNext()){
                        it.remove();
                    }
                }
            }catch (Exception e){
                log.error(e.getMessage(),e);
            }


        }

        //Process DbOps

        ListIterator<TimelineEntity> dbOpsIterator = timeline.listIterator();
        ClickEvent lastClickEvent = null;
        while (dbOpsIterator.hasNext()){
            try{
                TimelineEntity entity = dbOpsIterator.next();
                if(entity instanceof ClickEvent){
                    lastClickEvent = (ClickEvent) entity;
                }

                if (entity instanceof NetworkEvent){
                    NetworkEvent networkEvent = (NetworkEvent) entity;
                    if(!networkEvent.getMethod().toLowerCase().equals("get")){
                        ClickEvent finalLastClickEvent = lastClickEvent;
                        sqliteService.selectLogs(networkEvent.getMillisecondTimestamp(), 500).onSuccess(databaseOperations->{

                            try{
                                DbOps ops = new DbOps(databaseOperations.stream().map(o->(JsonObject)o).map(DbLogEntry::fromJson).collect(Collectors.toList()));

                                TermSupportAnalyzer.TermSupportAnalyzerBuilder builder = new TermSupportAnalyzer.TermSupportAnalyzerBuilder(ops, networkEvent);
                                if(finalLastClickEvent != null){
                                    builder.nearestPreceedingClickEvent(finalLastClickEvent);
                                }

                                TermSupportAnalyzer analyzer = builder.build();
                                String subject = ops.computeSubject(analyzer::getTermSupport);

                                if(subject == null){
                                    log.info("No support for a subject for this network event.");
                                }else{
                                    DbOps.Verb verb = ops.computeVerb(analyzer::getTermSupport, analyzer::getTermSupport);

                                    log.info("SEMANTIC LABEL: {} {}", verb.toString(), subject);
                                }


                            }catch (Exception innerE){
                                log.error(innerE.getMessage(), innerE);
                            }


                        });
                    }

                }
            }catch (Exception e){
                log.error(e.getMessage(),e);
            }
        }


        return Future.succeededFuture(timeline);
    }

    @Deprecated
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
