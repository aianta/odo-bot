package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.InputParameter;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();


    private OdoClient client = null;

    private ExecutionRequest activeExecutionRequest = null;

    private Request activeRequest = null;

    private List<NavPath> navPaths = null;

    private PathsRequestInput _input = null;

    private Transaction tx;

    public RequestManager(OdoClient client){
        this.client = client;
        this.client.setRequestManager(this);

    }

    public Request getActiveRequest() {
        return activeRequest;
    }

    public void setActiveRequest(Request activeRequest) {
        this.activeRequest = activeRequest;
    }

    public ExecutionRequest getActiveExecutionRequest() {
        return activeExecutionRequest;
    }

    public RequestManager setActiveExecutionRequest(ExecutionRequest activeExecutionRequest) {
        this.activeExecutionRequest = activeExecutionRequest;
        return this;
    }

    public void addNewRequest(ExecutionRequest request){
        setActiveExecutionRequest(request);
        client.getEventConnectionManager().startTransmitting()//Turn on event transmissions //TODO -> if something behaves oddly, this is a likely area where things might go wrong. Not sure when transmission should start.
                .compose(done->client.getEventConnectionManager().getLocalContext())
                .compose(localContext->getExecutionPath(request, localContext))
                .compose(executionInstruction->client.getGuidanceConnectionManager().sendExecutionInstruction(executionInstruction))
                .onSuccess(done->log.info("First instruction sent for execution!!"))
                .onFailure(err->{
                    log.error("Error occurred while processing execution request");
                    log.error(err.getMessage(), err);
                })
        ;
    }

    public void addNewRequest(Request request){
        setActiveRequest(request);
        client.getEventConnectionManager().getLocalContext()
                .compose(localContext->getPaths(request, localContext))
                .compose(navigationOptions->client.getGuidanceConnectionManager().showNavigationOptions(navigationOptions))
                .onSuccess(navigationOptionsShown->client.getEventConnectionManager().startTransmitting())
                .onFailure(err->{
                    log.error("Error processing guidance request");
                    log.error(err.getMessage(),err);
                });
        ;
    }

    public Future<JsonObject> getExecutionPath(ExecutionRequest request, JsonArray localContext){

        log.info("Processing localContext[size:{}] into ExecutionPathsRequestInput", localContext.size());

        eventProcessor.setOnEntity(this::buildPathsRequestInput, (entity)-> entity instanceof DataEntry || entity instanceof ClickEvent);
        eventProcessor.process(localContext);

        log.info("Done processing local context!");
        try{
            if(_input == null){
                //This happens when there is no meaningful local context. IE: local context that doesn't include a data entry or click event
                _input = new PathsRequestInput();
            }

            _input.setUserLocation(request.getUserLocation());
            _input.setTargetNode(request.getTarget().toString());
            _input.setPathRequestId(request.getId().toString());

            log.info("LastEntity: {}", _input.getLastEntity() != null?_input.lastEntity.symbol(): "N/A");
            log.info("URL: {}", _input.getUrl() != null? _input.getUrl(): "N/A");
            log.info("DOM: {}", _input.getDom() != null? _input.dom.toString().substring(0, Math.min(_input.dom.toString().length(), 150)): "N/A");
            log.info("TargetNode: {}", _input.targetNode);

            Optional<UUID> startingNode = LogPreprocessor.localizer.resolveStartingNode(_input);
            log.info("Found starting node? {}", startingNode.isPresent());

            UUID src = startingNode.get();
            UUID tgt = request.getTarget();

            log.info("Resolving execution path from {} to {}", src.toString(), tgt.toString());

            tx = LogPreprocessor.graphDB.db.beginTx();

            navPaths = LogPreprocessor.pathsConstructor.construct(tx, src,tgt, request.getParameters());

            log.info("Found {} execution paths", navPaths.size());

            JsonObject executionInstruction = buildExecutionInstruction(navPaths);

            return Future.succeededFuture(executionInstruction);
        }catch (Exception e){
            log.error(e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }

    public Future<JsonObject> getPaths(Request request,  JsonArray localContext){

        log.info("Processing local context[size:{}] into PathsRequestInput", localContext.size());

        //TODO: Should we really only be looking at DataEntries and ClickEvents, even for sourcing the last DOM and URL?
        eventProcessor.setOnEntity( this::buildPathsRequestInput , (entity)->entity instanceof DataEntry || entity instanceof ClickEvent);
        eventProcessor.process(localContext);

        log.info("Done processing local context!");
        try{
            if(_input == null){
                //This happens when there is no meaningful local context. IE: local context that doesn't include a data entry or click event
                _input = new PathsRequestInput();
            }

            _input.setUserLocation(request.getUserLocation());
            _input.setTargetNode(request.getTargetNode());
            _input.setPathRequestId(request.id().toString());
            log.info("LastEntity: {}", _input.getLastEntity() != null?_input.lastEntity.symbol(): "N/A");
            log.info("URL: {}", _input.getUrl() != null? _input.getUrl(): "N/A");
            log.info("DOM: {}", _input.getDom() != null? _input.dom.toString().substring(0, Math.min(_input.dom.toString().length(), 150)): "N/A");
            log.info("TargetNode: {}", _input.targetNode);


            Optional<UUID> startingNode = LogPreprocessor.localizer.resolveStartingNode(_input);
            log.info("Found starting node? {}", startingNode.isPresent());

            UUID src = startingNode.get();
            UUID tgt = UUID.fromString(request.getTargetNode());

            log.info("Resolving path from {} to {}", src.toString(), tgt.toString());

            tx = LogPreprocessor.graphDB.db.beginTx();

            navPaths = LogPreprocessor.pathsConstructor.construct(tx, src, tgt);

            log.info("Found {} paths", navPaths.size());

            buildNavigationOptions(navPaths);

            return Future.succeededFuture(new JsonObject().put("navigationOptions", buildNavigationOptions(navPaths)));
        }catch (Exception e){
            log.error(e.getMessage(), e);
            return Future.failedFuture(e);
        }


    }

    private void buildPathsRequestInput(TimelineEntity entity){

        PathsRequestInput result = new PathsRequestInput();
        result.setLastEntity(entity);

        if(entity instanceof ClickEvent){
            ClickEvent clickEvent = (ClickEvent) entity;
            result.setDom(clickEvent.getDomSnapshot());
            result.setUrl(clickEvent.getBaseURI());
        }

        if(entity instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) entity;
            result.setDom(dataEntry.lastChange().getDomSnapshot());
            result.setUrl(dataEntry.lastChange().getBaseURI());
        }

        _input = result;
    }

    private JsonObject buildExecutionInstruction(List<NavPath> paths){

        /**
         * We want to get distinct execution instructions from our set of possible paths. In fact, we have to reduce things to a single possible instruction.
         */
        List<JsonObject> possibleExecutionInstructions = paths.stream()
                .map(navPath -> navPath.getExecutionInstruction(getActiveExecutionRequest()))
                .distinct()
                .peek(instruction -> log.info("{}", instruction.getClass().getName()))
                .map(instruction -> {
                    if(instruction instanceof DoClick){
                        DoClick doClick = (DoClick)instruction;
                        return new JsonObject()
                                .put("action", "click")
                                .put("xpath", doClick.xpath);
                    }

                    if(instruction instanceof EnterData){
                        EnterData enterData = (EnterData) instruction;
                        return new JsonObject()
                                .put("action", "input")
                                .put("xpath", enterData.xpath)
                                .put("data", enterData.data)
                                ;
                    }

                    if(instruction instanceof QueryDom){
                        QueryDom queryDom = (QueryDom) instruction;
                        return new JsonObject()
                                .put("action", "queryDom")
                                .put("xpath", queryDom.dynamicXPath.toJson())
                                .put("parameterId", queryDom.parameterId);
                    }

                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
        ;

        log.info("Computed {} possible execution instructions, sending the first one!", possibleExecutionInstructions.size());

        JsonObject executionInstruction = possibleExecutionInstructions.get(0);
        log.info("{}", executionInstruction.encodePrettily());

        return executionInstruction;
    }

    private JsonArray buildNavigationOptions(List<NavPath> paths){


        /**
         * We want to get distinct instructions from our set of possible paths. Many paths will overlap
         * and thus may have identical instructions.
         */
        JsonArray navOptions = paths.stream()
                .map(NavPath::getInstruction)
                .distinct()
                .peek(instruction -> {
                    if(instruction instanceof XPathInstruction){
                        log.info(" {} - {}", instruction.hashCode(), ((XPathInstruction)instruction).xpath);
                    }else{
                        log.info("{} - {}", instruction.hashCode(), ((DynamicXPathInstruction)instruction).dynamicXPath.toJson().encodePrettily());
                    }
                })
                //Convert instruction objects into JsonObjects
                .map(instruction -> {
                    if(instruction instanceof XPathInstruction){
                        XPathInstruction xPathInstruction = (XPathInstruction) instruction;
                        return new JsonObject().put("type", "guidance").put("xpath", xPathInstruction.xpath);
                    }

                    if(instruction instanceof DynamicXPathInstruction){
                        DynamicXPathInstruction dynamicXPathInstruction = (DynamicXPathInstruction) instruction;
                        return new JsonObject().put("type", "guidance").put("xpath", dynamicXPathInstruction.dynamicXPath.toJson());
                    }

                    return null;
                })
                //Filter out any nulls, and collect it all into a JsonArray
                .filter(Objects::nonNull)
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);


        log.info("Produced {} instructions/unique navigation options.", navOptions.size());

        return navOptions;

    }

    /**
     * This method checks if a given timeline entity matches corresponds with the specified target node for the active request.
     *
     * @param entity
     */
    public void pathCompletionWatcher(TimelineEntity entity){

        NetworkEvent apiCall = (NetworkEvent) entity; //Note: When we set up this watcher we ensure it only receives network events.
        if(
                apiCall.getMethod().toLowerCase().equals(activeRequest.getTargetMethod().toLowerCase()) && //If the method matches
                apiCall.getPath().equals(activeRequest.getTargetPath())
        ){
            client.getGuidanceConnectionManager().notifyPathComplete();
            client.getEventConnectionManager().notifyPathComplete();
        }
    }

    /**
     * This method checks if a given timeline entity matches an instruction that was given to the user. If so, it computes the next instruction
     * to give to the user.
     *
     * @param entity
     */
    public void instructionWatcher(TimelineEntity entity){

        if(navPaths == null){ // If there are no navigation paths currently being managed for this request, then we can ignore realtime events.
            return;
        }

        String xpath = null;

        if(entity instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) entity;
            xpath = dataEntry.lastChange().getXpath();
        }

        if(entity instanceof ClickEvent){
            ClickEvent clickEvent = (ClickEvent) entity;
            xpath = clickEvent.getXpath();
        }

        final String observedXPath = xpath;
        log.info("Observed {} on xpath: {}", entity.symbol(), observedXPath);

        /**
         * Update the navPaths associated with this request.
         * Prune all paths whose last instruction was not followed by the user.
         */
        List<NavPath> tempNavPaths = navPaths.stream()
                .filter(navPath -> {
                    Instruction lastInstruction = navPath.lastInstruction();

                    if(lastInstruction == null){
                        log.error("Last instruction is null!");
                        throw new RuntimeException("Last instruction was null!");
                    }

                    if(lastInstruction instanceof XPathInstruction){
                        return observedXPath.equals(((XPathInstruction) lastInstruction).xpath);
                    }

                    if(lastInstruction instanceof DynamicXPathInstruction){
                        DynamicXPathInstruction dynamicXPathInstruction = (DynamicXPathInstruction) lastInstruction;
                        return dynamicXPathInstruction.dynamicXPath.matches(observedXPath) || dynamicXPathInstruction.dynamicXPath.stillMatches(observedXPath);
                    }


                    log.warn("Unknown instruction type detected in navPath.lastInstruction()");
                    return false;
                })
                .collect(Collectors.toList())
        ;

        if(tempNavPaths.size() > 0){
            this.navPaths = tempNavPaths;

            if(getActiveExecutionRequest() != null){
                client.getGuidanceConnectionManager()
                        .sendExecutionInstruction(buildExecutionInstruction(tempNavPaths));
            }else{
                client.getGuidanceConnectionManager().showNavigationOptions(
                        new JsonObject().put("navigationOptions", buildNavigationOptions(this.navPaths))
                );
            }
        }
    }


}
