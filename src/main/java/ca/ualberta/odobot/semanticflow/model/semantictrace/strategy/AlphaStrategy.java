package ca.ualberta.odobot.semanticflow.model.semantictrace.strategy;

import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.semanticflow.model.semantictrace.SemanticLabel;
import ca.ualberta.odobot.semanticflow.model.semantictrace.SemanticTrace;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.sqlite.impl.DbLogEntry;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class AlphaStrategy extends AbstractStrategy implements SemanticTraceConstructionStrategy{

    private static final Logger log = LoggerFactory.getLogger(AlphaStrategy.class);
    public AlphaStrategy(SqliteService sqliteService) {
        super(sqliteService);
    }

    @Override
    public String name() {
        return "AlphaStrategy";
    }

    @Override
    public Future<SemanticTrace> construct(Timeline timeline) {
        Promise<SemanticTrace> promise = Promise.promise();

        //Process DbOps by iterating through the entities in this timeline.
        ListIterator<TimelineEntity> dbOpsIterator = timeline.listIterator();

        //Keep track of the closest preceeding click event
        ClickEvent lastClickEvent = null;

        List<Future> semanticLabelFutures = new ArrayList<>();

        while (dbOpsIterator.hasNext()){
            try{
                //Get the timeline entity
                TimelineEntity entity = dbOpsIterator.next();

                //If it is a click event, update our last click event
                if(entity instanceof ClickEvent){
                    lastClickEvent = (ClickEvent) entity;
                }

                //If it is a network event
                if (entity instanceof NetworkEvent){

                    //Cast to network event
                    NetworkEvent networkEvent = (NetworkEvent) entity;

                    /*
                        Determine if it is a POST, PUT, DELETE anything but a get will trigger an attempt to compute a semantic label.
                        In theory this is because non-get requests indicate a domain action of some sort, data is being explicitly
                        sent to the server for some reason, and here we try to figure out what that reason was.
                     */
                    if(!networkEvent.getMethod().toLowerCase().equals("get")){

                        //Temporary variable to make the last click event available in the lambda.
                        ClickEvent finalLastClickEvent = lastClickEvent;

                        /*
                         Go fetch the database audit logs corresponding to the millisecond timestamp on the network event.
                         We specify a range from which to capture. For example, if the network timestamp is 6000, and the range
                         is 500. Then we fetch all audit logs from timestamps 5500 to 6500.
                         */

                        Future<Optional<SemanticLabel>> labelFuture =
                                sqliteService.selectLogs(networkEvent.getMillisecondTimestamp(), 500).<Optional<SemanticLabel>>compose(databaseOperations->{

                                            /**
                                             * databaseOperations here is going to be a JsonArray of DbLogEntries in JsonObject forms.
                                             * First thing we do is bring them back into DbLogEntry form, and collect them into a List<DbLogEntry>.
                                             * DbOps extends such a list and can thus be initialized with it.
                                             */
                                            try{
                                                DbOps ops = new DbOps(databaseOperations.stream()
                                                        .map(o->(JsonObject)o)
                                                        .map(DbLogEntry::fromJson)
                                                        .collect(Collectors.toList()));

                                                networkEvent.setDbOps(ops);

                                                log.info("DB OPS: {}", ops.toJson().encodePrettily());

                                                TermSupportAnalyzer.TermSupportAnalyzerBuilder builder = new TermSupportAnalyzer.TermSupportAnalyzerBuilder(ops, networkEvent);
                                                if(finalLastClickEvent != null){
                                                    builder.nearestPreceedingClickEvent(finalLastClickEvent);
                                                }

                                                TermSupportAnalyzer analyzer = builder.build();
                                                SemanticLabel label = new SemanticLabel();

                                                LinkedHashMap<String, List<List<String>>> tableNgrams = ops.getTableNGrams();
                                                LinkedHashMap<String, Double> support = new LinkedHashMap<>();
                                                tableNgrams.entrySet().forEach(entry->{
                                                    List<List<String>> ngrams = entry.getValue();

                                                    double tableSupport = 0.0;
                                                    Iterator<List<String>> it = ngrams.iterator();
                                                    while (it.hasNext()){
                                                        List<String> ngram = it.next();
                                                        double nGramSupport  = analyzer.getNGramSupport(ngram);
                                                        tableSupport += nGramSupport;
                                                    }

                                                    support.put(entry.getKey(), tableSupport);

                                                });

                                                String topTable = support.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).findFirst().get().getKey();





                                                LinkedHashMap<DbOps.Verb, List<List<String>>> verbGrams = ops.getVerbGrams();
                                                LinkedHashMap<DbOps.Verb, Double> verbsSupport = new LinkedHashMap();
                                                verbGrams.entrySet().forEach(entry->{
                                                    List<List<String>> ngrams = entry.getValue();
                                                    double verbSupport = 0.0;
                                                    Iterator<List<String>> it = ngrams.iterator();
                                                    while (it.hasNext()){
                                                        List<String> ngram = it.next();
                                                        double nGramSupport = analyzer.getNGramSupport(ngram);
                                                        verbSupport += nGramSupport;
                                                    }

                                                    verbsSupport.put(entry.getKey(), verbSupport);
                                                });

                                                String topVerb = verbsSupport.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).findFirst().get().getKey().name();

                                                //If the top table has an insert record in this set of database operations, but create has insufficient support from the UI data, override that.
                                                if(ops.hasInsert(label.getSubject()) && !topVerb.equals(DbOps.Verb.CREATE)){
                                                    verbsSupport.put(DbOps.Verb.CREATE, verbsSupport.get(DbOps.Verb.CREATE) + Double.POSITIVE_INFINITY);
                                                    topVerb = DbOps.Verb.CREATE.name();
                                                }
                                                //If the topVerb is CREATE, and the top table does not have an insert record.
                                                if(topVerb.equals(DbOps.Verb.CREATE.name()) && !ops.hasInsert(topTable)){
                                                    //Get the next top table that HAS an insert record.
                                                    Optional<Map.Entry<String,Double>> newTopTable = support.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).filter(tableEntry->ops.hasInsert(tableEntry.getKey())).findFirst();
                                                    if(newTopTable.isEmpty()){
                                                        log.error("Edge case, top verb is CREATE, but no top table with insert could be found!");
                                                        return Future.succeededFuture(Optional.empty());
                                                    }else{

                                                        topTable = newTopTable.get().getKey();
                                                        support.put(topTable, support.get(topTable) + Double.POSITIVE_INFINITY);
                                                    }
                                                }

                                                support.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).collect(Collectors.toList())
                                                        .forEach(entry->label.getSubjectSupport().put(entry.getKey(), entry.getValue()));

                                                verbsSupport.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                                                        .collect(Collectors.toList()).forEach(entry->label.getVerbSupport().put(entry.getKey().name(), entry.getValue()));

                                                label.setVerb(topVerb);
                                                label.setSubject(topTable);
                                                label.getAnnotations().put("clickEvent", finalLastClickEvent.getSemanticArtifacts().getJsonArray("terms"));
                                                label.getAnnotations().put("networkEvent", networkEvent.toJson());

                                                return Future.succeededFuture(Optional.of(label));


                                            }catch (Exception innerE){
                                                log.error(innerE.getMessage(), innerE);
                                            }

                                            return Future.succeededFuture(Optional.empty());

                                        })
                                        .onFailure(err->log.error(err.getMessage(), err));

                        semanticLabelFutures.add(labelFuture);
                    }

                }
            }catch (Exception e){
                log.error(e.getMessage(),e);
            }
        }

        if(semanticLabelFutures.size() > 0){
            CompositeFuture.all(semanticLabelFutures)
                    .onSuccess(results->{
                        SemanticTrace trace = new SemanticTrace(results.<Optional<SemanticLabel>>list().stream()
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .collect(Collectors.toList()));

                        trace.setConstructionStrategy(name());
                        trace.setSourceIndex(timeline.getAnnotations().getString("source-index"));

                        promise.complete(trace);
                    });

        }else{
            SemanticTrace emptyTrace = new SemanticTrace();
            emptyTrace.setConstructionStrategy(name());
            emptyTrace.setSourceIndex(timeline.getAnnotations().getString("source-index"));
            promise.complete(emptyTrace);
        }

        return promise.future();

    }
}
