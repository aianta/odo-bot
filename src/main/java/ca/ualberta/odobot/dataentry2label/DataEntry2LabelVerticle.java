package ca.ualberta.odobot.dataentry2label;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.CheckboxNode;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.DataEntryNode;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.NavNode;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.RadioButtonNode;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.serviceproxy.ServiceBinder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class DataEntry2LabelVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(DataEntry2LabelVerticle.class);

    private static Neo4JUtils neo4j;

    @Override
    public String serviceName() {
        return "DataEntry2Label Service";
    }

    @Override
    public String configFilePath() {
        return "config/dataentry2label.yaml";
    }

    public static SqliteService sqliteService;

    public static ElasticsearchService elasticsearchService;

    public static DataEntry2LabelService dataEntry2LabelService;

    public Completable onStart(){
        super.onStart();

        //Init and expose DataEntry2Label Service
        dataEntry2LabelService = DataEntry2LabelService.create(vertx.getDelegate(), _config, Strategy.OPENAI);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(DATA_ENTRY_2_LABEL_SERVICE_ADDRESS)
                .register(DataEntry2LabelService.class, dataEntry2LabelService);

        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);

        //Init ElasticSearch Service Proxy
        elasticsearchService = ElasticsearchService.createProxy(vertx.getDelegate(), ELASTICSEARCH_SERVICE_ADDRESS);

        //Init Neo4j Utils
        neo4j = new Neo4JUtils("bolt://localhost:7687", "neo4j", "odobotdb");


        api.route().method(HttpMethod.GET).path("/processDataEntries")
                .handler(this::processFlights);

        api.route().method(HttpMethod.GET).path("/generateLabels")
                .handler(this::generateDataEntryLabels);

        api.route().method(HttpMethod.GET).path("/annotateModel")
                .handler(this::annotateModel);

        return Completable.complete();
    }

    private void annotateModel(RoutingContext rc){

        List<NavNode> nodesForInputParameters = neo4j.getNodesForInputParameters();

        sqliteService.getAllDataEntryAnnotations()
                .onSuccess(annotations->{

                    nodesForInputParameters.stream()
                            .forEach(node->{

                                Optional<JsonObject> parameterAnnotation = annotations.stream().filter(annotation->{
                                    if(node instanceof RadioButtonNode){
                                        return ((RadioButtonNode)node).getXpaths().contains(annotation.getString("xpath"));
                                    }else if (node instanceof DataEntryNode){
                                        return annotation.getString("xpath").equals(((DataEntryNode)node).getXpath());
                                    }else if (node instanceof CheckboxNode){
                                        return annotation.getString("xpath").equals(((CheckboxNode)node).getXpath());
                                    }

                                    return false;

                                }).findFirst();

                                if(parameterAnnotation.isPresent()){
                                    UUID parameterNodeId = neo4j.addInputParameter(parameterAnnotation.get(), node.getId().toString());
                                    log.info("Created input parameter node {}", parameterNodeId.toString());
                                }

                            });

                    rc.response().setStatusCode(200).end();

                })
                .onFailure(err->{
                    log.error(err.getMessage(), err);
                    rc.response().setStatusCode(500).end(err.getMessage());
                })
        ;


    }

    private void generateDataEntryLabels(RoutingContext rc){

        sqliteService.getAllDataEntryInfo()
                .compose(dataEntries-> Future.all(dataEntries.stream().map(dataEntry->dataEntry2LabelService.generateLabelAndDescription(dataEntry)).collect(Collectors.toList())))
                .compose(compositeFuture->{
                    List<JsonObject> results = compositeFuture.list();
                    log.info("Generated {} labels and descriptions. Saving them to the database now.", results.size());

                    rc.put("dataEntryLabels", results);

                    return Future.all(
                            results.stream().map(sqliteService::saveDataEntryAnnotation).collect(Collectors.toList())
                    );
                })
                .onSuccess(done->{

                    log.info("Data entry annotations saved!");

                    JsonArray response = ((List<JsonObject>)rc.get("dataEntryLabels")).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                    rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(response.encodePrettily());
                })
                .onFailure(err->{
                    log.error(err.getMessage(),err);
                    rc.response().setStatusCode(500).end(err.getMessage());
                })

        ;

    }

    private void processFlights(RoutingContext rc){

        String elasticSearchIndex = rc.request().getParam("index", "selenium-test-gb");

        //Fetch all the flights contained in the specified index.
        elasticsearchService.getFlights(elasticSearchIndex, "flightID.keyword")
                .compose(flightSet->{

                    log.info("Got {} flights/timelines to process", flightSet.size());

                    Future<Void> f = Future.succeededFuture();

                    Iterator<String> flightIterator = flightSet.iterator();
                    while (flightIterator.hasNext()){
                        String flightId = flightIterator.next();

                        f = f.compose(done->processTimeline(elasticSearchIndex, flightId, "flightID.keyword"));

                    }

                    return f;
                }).onSuccess(done->{
                    log.info("Finished processing flights->timelines->data entries->data entry prompt info. Info saved to database!");
                    rc.response().setStatusCode(200).end();
                })
                .onFailure(err->{
                    log.error(err.getMessage(), err);
                    rc.response().setStatusCode(500).end(err.getMessage());
                })
        ;


    }

    private String getHTMLContext(String domSnapshot, String xpath){

        domSnapshot = domSnapshot.replaceAll("iframe", "div");

        Document document = Jsoup.parse(domSnapshot);

        Element inputElement = document.selectXpath(xpath).first();

        String result = null;

        try{
            result = getHTMLContext(inputElement);
        }catch (Exception e){
            try{
                Files.deleteIfExists(Path.of("state.html"));
                Files.write(Path.of("state.html"), domSnapshot.getBytes(), StandardOpenOption.CREATE_NEW);
                //log.info("Input element is contained in DOMSnapshot: {}",domSnapshot.contains(inputElement.outerHtml()));
                log.info("xpath: {}", xpath);

            } catch (IOException ioe) {
                log.error(ioe.getMessage(), ioe);
                throw new RuntimeException(ioe);
            }
        }

        return result;
    }

    private String getHTMLContext(Element inputElement) throws Exception {
        if(inputElement != null){

            Element form = getForm(inputElement);

            //If the input element was part of a form, return the outerHTML of the form as the HTML context.
            if(form != null){
                return form.outerHtml();
            }

            //Otherwise, try and go up two parents and return that.
            int levels = 0;
            while (inputElement.hasParent() && levels < 3){
                inputElement = inputElement.parent();
                levels++;
            }

            return inputElement.outerHtml();

        }

        log.error("Could not locate input element using xpath! Cannot produce HTML context...");



        throw new Exception("Could not locate input element using xpath! Cannot produce HTML context...");
    }

    /**
     *
     * @param element
     * @return true if the given element has an ancestor whose tag is 'form'
     */
    private Element getForm(Element element){

        //Handle trivial case where the element itself is the form. This shouldn't really happen.
        if(element.tagName().toLowerCase().equals("form")){
            return element;
        }

        while (element.hasParent()){
            element = element.parent();
            if(element.tagName().toLowerCase().equals("form")){
                return element;
            }
        }

        //Element was not a child of a form element.
        return null;
    }

    private Future processTimeline(String index, String flightId, String flightIdentifierField){

        /**
         * Do this in a worker thread to avoid blocking the main loop.
         */
        return vertx.getDelegate().executeBlocking(blocking->{

            log.info("Processing timeline/flight {}", flightId);

            elasticsearchService.fetchFlightEvents(index, flightId, flightIdentifierField, new JsonArray())
                    .compose(flightEvents->Future.succeededFuture(this.makeTimeline(flightId, flightEvents)))
                    .compose(timeline->{

                        return Future.all(timeline.stream()
                                .filter(entity->entity instanceof DataEntry || entity instanceof CheckboxEvent || entity instanceof RadioButtonEvent) //Data entries, checkbox events, and radio button events are all based on InputChanges
                                .map(entity->{

                                    if(entity instanceof RadioButtonEvent){
                                        RadioButtonEvent radioButtonEvent = (RadioButtonEvent)entity;

                                        //The DomSnapshot must contain the input element, otherwise what are we doing?
                                        assert  radioButtonEvent.getDomSnapshot().outerHtml().contains(radioButtonEvent.getInputElement().outerHtml());

                                        //Assemble the HTML context as a concatenation of all radio button options.
                                        StringBuilder htmlContext = new StringBuilder();
                                        radioButtonEvent.getOptions().forEach(radioButton -> htmlContext.append(radioButton.getHtml() + "\n"));

                                        JsonObject data = new JsonObject()
                                                .put("input_element", radioButtonEvent.getInputElement().outerHtml())
                                                .put("html_context", htmlContext.toString())
                                                .put("radio_group", radioButtonEvent.getRadioGroup())
                                                .put("xpath", radioButtonEvent.getXpath());

                                        return data;
                                    }


                                    if(entity instanceof CheckboxEvent){
                                        CheckboxEvent checkboxEvent = (CheckboxEvent) entity;

                                        //The DomSnapshot must contain the input element, otherwise what are we doing?
                                        assert checkboxEvent.getDomSnapshot().outerHtml().contains(checkboxEvent.getInputElement().outerHtml());

                                        String htmlContext = getHTMLContext(checkboxEvent.getDomSnapshot().outerHtml(),checkboxEvent.xpath());
                                        if(htmlContext == null){
                                            return null;
                                        }

                                        JsonObject data = new JsonObject()
                                                .put("input_element", checkboxEvent.getInputElement().outerHtml())
                                                .put("html_context", htmlContext)
                                                .put("xpath", checkboxEvent.xpath());

                                        return data;
                                    }

                                    if(entity instanceof DataEntry){
                                        DataEntry dataEntry = (DataEntry) entity;

                                        JsonObject data = new JsonObject()
                                                .put("input_element", dataEntry.inputElement().outerHtml())
                                                .put("entered_data", dataEntry.getEnteredData())
                                                .put("xpath", dataEntry.xpath());

                                        //For tinyMCE events, lets leave the HTML context as the input element.
                                        if (dataEntry.lastChange() instanceof TinymceEvent){
                                            data.put("html_context", dataEntry.inputElement().outerHtml());
                                            return data;
                                        }

                                        //The DomSnapshot must contain the input element, otherwise what are we doing?
                                        assert dataEntry.lastChange().getDomSnapshot().outerHtml().contains(dataEntry.inputElement().outerHtml());

                                        String htmlContext = getHTMLContext(dataEntry.lastChange().getDomSnapshot().outerHtml(), dataEntry.xpath());
                                        if(htmlContext == null){
                                            return null;
                                        }

                                        data.put("html_context", htmlContext);

                                        return data;
                                    }

                                    log.warn("Unexpected entity type! {}", entity.getClass().getName());
                                    return null;

                                })
                                .filter(Objects::nonNull)
                                .map(json->sqliteService.saveDataEntryInfo(json))
                                .collect(Collectors.toList()));
                    }).compose(done->{
                        log.info("Finished processing data entries for timeline/flight {}", flightId);
                        return Future.succeededFuture();
                    })
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
            ;

        });
    }

    private Timeline makeTimeline(String flightName, List<JsonObject> events){

        SemanticSequencer sequencer = new SemanticSequencer();
        sequencer.updateIncludeFilter(Set.of(InteractionType.INPUT));

        //Timeline data structure construction
        Timeline timeline = sequencer.parse(events);
        //TODO: this might fail if we ever actually use flight names instead of IDs.
        UUID timelineId = UUID.fromString(flightName);
        timeline.setId(timelineId);
        timeline.getAnnotations().put("flight-name", flightName);

        return timeline;
    }
}
