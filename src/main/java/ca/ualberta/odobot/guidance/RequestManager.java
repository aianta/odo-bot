package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.execution.ExecutionParameter;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.InputParameter;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.taskplanner.TaskPlannerVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();

    private Promise<Void> evaluationComplete;

    private OdoClient client = null;

    private ExecutionRequest activeExecutionRequest = null;

    private String evalId = null;

    private List<NavPath> navPaths = null;

    private PathsRequestInput _input = null;

    private Transaction tx;

    public RequestManager(OdoClient client){
        this.client = client;
        this.client.setRequestManager(this);

    }

    public String getEvalId() {
        return evalId;
    }

    public RequestManager setEvalId(String evalId) {
        this.evalId = evalId;
        return this;
    }

    public Promise<Void> getEvaluationComplete() {
        return evaluationComplete;
    }

    public RequestManager setEvaluationComplete(Promise<Void> evaluationComplete) {
        this.evaluationComplete = evaluationComplete;
        this.evaluationComplete.future().onSuccess(done->{
            //Clear timeout timer
            GuidanceVerticle._vertx.cancelTimer(client.getGuidanceConnectionManager().timeoutTimer);
        });
        this.evaluationComplete.future().onFailure(err->{
            log.error(err.getMessage(), err);
            client.getEventConnectionManager().getEventProcessor().saveRawEvents("./%s/%s.json".formatted("execution_events", evalId).replaceAll("\\|","-"));
        });
        return this;
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


    public Future<JsonObject> getExecutionPath(ExecutionRequest request, JsonArray localContext){

        log.info("Processing localContext[size:{}] into ExecutionPathsRequestInput", localContext.size());

        eventProcessor.setOnEntity(this::buildPathsRequestInput, (entity)-> entity instanceof DataEntry || entity instanceof ClickEvent || entity instanceof CheckboxEvent);
        eventProcessor.process(localContext);

        log.info("Done processing local context!");
        try{
            if(_input == null){
                //This happens when there is no meaningful local context. IE: local context that doesn't include a data entry or click event
                _input = new PathsRequestInput();
            }

            _input.setUserLocation(request.getUserLocation());

            _input.setPathRequestId(request.getId().toString());

            log.info("LastEntity: {}", _input.getLastEntity() != null?_input.lastEntity.symbol(): "N/A");
            log.info("URL: {}", _input.getUrl() != null? _input.getUrl(): "N/A");
            log.info("DOM: {}", _input.getDom() != null? _input.dom.toString().substring(0, Math.min(_input.dom.toString().length(), 150)): "N/A");
            log.info("User Location: {}",  _input.getUserLocation() != null? _input.getUserLocation(): "N/A");

            Optional<UUID> startingNode = LogPreprocessor.localizer.resolveStartingNode(_input);
            log.info("Found starting node? {}", startingNode.isPresent());

            UUID src = startingNode.get();

            //Handle Natural Language tasks
            if(request.getType() == ExecutionRequest.Type.NL){
                log.info("Natural language task request");
                //Resolve the parameter associated input and object nodes
                //Basically the node IDs in the task definition correspond with the actual, Input and Schema parameter nodes.
                //What we actually want, are the ids of the nodes associated with those input and schema parameter nodes (as defined by the PARAM edge).
                Set<String> inputParameters = request.getParameters().stream()
                        .filter(p->p.getType().equals(ExecutionParameter.ParameterType.InputParameter))
                        .map(p->LogPreprocessor.neo4j.getParameterAssociatedNodes(p.getNodeId().toString()))
                        .collect(HashSet::new, HashSet::addAll, HashSet::addAll);

                Set<String> objectParameters = request.getParameters().stream()
                        .filter(p->p.getType().equals(ExecutionParameter.ParameterType.SchemaParameter))
                        .map(p->LogPreprocessor.neo4j.getParameterAssociatedNodes(p.getNodeId().toString()))
                        .collect(HashSet::new, HashSet::addAll,HashSet::addAll);

                Set<String> apiCalls = request.getTargets();

                //Save these for later if we need to re-compute paths for this request.
                request.setApiCalls(apiCalls);
                request.setInputParameters(inputParameters);
                request.setObjectParameters(objectParameters);

                tx = LogPreprocessor.graphDB.db.beginTx();

                navPaths = LogPreprocessor.pathsConstructor.construct(tx, src.toString(), objectParameters, inputParameters, apiCalls);

                //First collect together our parameter mappings, we'll need this to generate semantically meaningful natural language descriptions of the different paths.
                JsonArray parameters = request.getParameters().stream().map(ExecutionParameter::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                //Different nav paths only matter if they involve different interactions.
                //We can determine if they actually have unique sets of interactions by converting them to natural language and ensuring the uniqueness of the output.
                Set<String> uniqueNavPaths = new HashSet<>();
                Iterator<NavPath> pathIt = navPaths.iterator();
                while (pathIt.hasNext()){
                    NavPath p = pathIt.next();
                    JsonArray nlPath = p.toNaturalLanguage(parameters).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
                    int prevSize = uniqueNavPaths.size();
                    uniqueNavPaths.add(nlPath.encode());
                    if(prevSize == uniqueNavPaths.size()){
                        pathIt.remove();
                    }
                }

                log.info("Found {} execution paths", navPaths.size());
                if(navPaths.size() > 1){

                    //Now we prompt the LLM to decide between the paths we were able to find. This is where/how the system decides between create/edit paths for example.


                    //Create a JsonObject containing all the different path options.
                    //Each entry in the object is going to be <navPathID> : <Natural language steps in JsonArray>
                    JsonObject paths = new JsonObject();

                    navPaths.forEach(navPath -> paths.put(navPath.getId().toString(), navPath.toNaturalLanguage(parameters).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll)));
                    return TaskPlannerVerticle.service.selectPath(paths, request.getTaskDescription())
                            .onFailure(err->log.error(err.getMessage(), err))
                            .compose(chosenPathId->{
                                NavPath chosenPath = navPaths.stream().filter(navPath -> navPath.getId().equals(UUID.fromString(chosenPathId))).findFirst().get();
                                navPaths = List.of(chosenPath);

                                //Save the navPath for this request.
                                NavPath.saveNavPath("./%s/%s-navpath.txt".formatted("execution_events", evalId).replaceAll("\\|","-"), chosenPath);

                                /**
                                 * We still need a target node so that the execution mechanism can determine when the task has been completed.
                                 * All paths produced using the new path construction logic will end in an API node.
                                 *
                                 * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
                                 * be our target node.
                                 */
                                var targetNodeId = UUID.fromString(chosenPath.getPath().endNode().getProperty("id").toString());
                                _input.setTargetNode(targetNodeId.toString());
                                request.setTarget(targetNodeId);

                                JsonObject executionInstruction = buildExecutionInstruction(navPaths);
                                if(executionInstruction == null){
                                    /**
                                     * This method (getExecutionPath) is only called on to produce the first instruction for the execution.
                                     * If the chosen path begins with a LocationNode (which is common), then the first instruction would
                                     * be to wait for that location change. But since we likely just initialized our local context. There's
                                     * not going to be an application location change event.
                                     *
                                     * To deal with this, if the execution instruction is null, as would be the case for WaitFor type instructions
                                     * (because they don't send anything to OdoX, the instruction JSON is null), call buildExecutionInstruction again
                                     * to get the next instruction.
                                     *
                                     * TODO: refactor this. This method is doing too much. And this 'temporary fix' only adds to the complexity of the execution logic.
                                     */
                                    executionInstruction = buildExecutionInstruction(navPaths);
                                }

                                return Future.succeededFuture(executionInstruction);

                            });
                }



                navPaths = List.of(navPaths.get(0)); //Only return/use the first path for execution. TODO: leveraging multiple paths + using them to fallback could be an interesting direction to explore.

                //Save the navPath for this request.
                NavPath.saveNavPath("./%s/%s-navpath.txt".formatted("execution_events", evalId).replaceAll("\\|","-"), navPaths.get(0));

                /**
                 * We still need a target node so that the execution mechanism can determine when the task has been completed.
                 * All paths produced using the new path construction logic will end in an API node.
                 *
                 * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
                 * be our target node.
                 */
                var targetNodeId = UUID.fromString(navPaths.get(0).getPath().endNode().getProperty("id").toString());
                _input.setTargetNode(targetNodeId.toString());
                request.setTarget(targetNodeId);

                log.info("Found {} execution paths", navPaths.size());
            }

            //Handle tasks that have been pre-defined in terms of the navigational model.
            if(request.getType() == ExecutionRequest.Type.PREDEFINED){
                _input.setTargetNode(request.getTarget().toString());
                log.info("TargetNode: {}", _input.targetNode);

                UUID tgt = request.getTarget();

                log.info("Resolving execution path from {} to {}", src.toString(), tgt.toString());

                tx = LogPreprocessor.graphDB.db.beginTx();

                navPaths = LogPreprocessor.pathsConstructor.construct(tx, src,tgt, request.getParameters());

                log.info("Found {} execution paths", navPaths.size());

            }

            JsonObject executionInstruction = buildExecutionInstruction(navPaths);
            if(executionInstruction == null){
                /**
                 * This method (getExecutionPath) is only called on to produce the first instruction for the execution.
                 * If the chosen path begins with a LocationNode (which is common), then the first instruction would
                 * be to wait for that location change. But since we likely just initialized our local context. There's
                 * not going to be an application location change event.
                 *
                 * To deal with this, if the execution instruction is null, as would be the case for WaitFor type instructions
                 * (because they don't send anything to OdoX, the instruction JSON is null), call buildExecutionInstruction again
                 * to get the next instruction.
                 *
                 * TODO: refactor this. This method is doing too much. And this 'temporary fix' only adds to the complexity of the execution logic.
                 */
                executionInstruction = buildExecutionInstruction(navPaths);
            }

            return Future.succeededFuture(executionInstruction);

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
            result.setUrl(clickEvent.getBaseURI().toString());
        }

        if(entity instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) entity;
            result.setDom(dataEntry.lastChange().getDomSnapshot());
            result.setUrl(dataEntry.lastChange().getBaseURI().toString());
        }

        if(entity instanceof CheckboxEvent){
            CheckboxEvent checkboxEvent = (CheckboxEvent) entity;
            result.setDom(checkboxEvent.getDomSnapshot());
            result.setUrl(checkboxEvent.getBaseURI().toString());
        }

        _input = result;
    }

    private JsonObject buildExecutionInstruction(List<NavPath> paths){

        /**
         * We want to get distinct execution instructions from our set of possible paths. In fact, we have to reduce things to a single possible instruction.
         */
        List<JsonObject> possibleExecutionInstructions = paths.stream()
                .peek(navPath -> NavPath.printNavPaths(List.of(navPath),100))
                .map(navPath -> navPath.getExecutionInstruction(getActiveExecutionRequest()))

                .filter(Objects::nonNull)
                .distinct()
                .peek(instruction -> {log.info("{}", instruction.getClass().getName());

                    if(instruction instanceof WaitForLocationChange){
                        log.info("Waiting for location to change to: {}", ((WaitForLocationChange) instruction).path);
                    }

                })
                .map(instruction -> {
                    if(instruction instanceof DoClick){
                        DoClick doClick = (DoClick)instruction;
                        return new JsonObject()
                                .put("action", "click")
                                .put("xpath", doClick.xpath);
                    }

                    if (instruction instanceof EnterDataTinymce){
                        EnterDataTinymce enterDataTinymce = (EnterDataTinymce) instruction;
                        return new JsonObject()
                                .put("action", "input")
                                .put("xpath", enterDataTinymce.xpath)
                                .put("editorId", enterDataTinymce.editorId)
                                .put("data", enterDataTinymce.data)
                                ;

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


                    //Expect to return null for WaitForLocationChange and WaitForNetworkEvent instructions.
                    //This is because they do not entail sending any instructions to OdoX. Rather, they are instructions for the server
                    //to wait for the specified interactions to take place in the online timeline.
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
        ;

        log.info("Computed {} possible execution instructions, sending the first one!", possibleExecutionInstructions.size());

        //No instructions left, return null.
        if(possibleExecutionInstructions.size() == 0){
            return null;
        }

        JsonObject executionInstruction = possibleExecutionInstructions.get(0);
        log.info("{}", executionInstruction.encodePrettily());

        return executionInstruction;
    }


    /**
     * This method checks if a given timeline entity matches corresponds with the specified target node for the active request.
     *
     * @param entity
     */
    public void pathCompletionWatcher(TimelineEntity entity){


        NetworkEvent apiCall = (NetworkEvent) entity; //Note: When we set up this watcher we ensure it only receives network events.


        if(
                apiCall.getMethod().toLowerCase().equals(activeExecutionRequest.getTargetMethod().toLowerCase()) && //If the method matches
                        apiCall.getPath().equals(activeExecutionRequest.getTargetPath())
        ){
            client.getGuidanceConnectionManager().notifyPathComplete();
            client.getEventConnectionManager().notifyPathComplete();

            if(evalId != null && evaluationComplete != null){
                //If the evaluation ID is not null (meaning this was an evaluation run, output the raw events from this execution to the appropriate folder.
                client.getEventConnectionManager().getEventProcessor().saveRawEvents("./%s/%s.json".formatted("execution_events", evalId).replaceAll("\\|","-"));
                evaluationComplete.complete();
            }
            client.getEventConnectionManager().getEventProcessor().clearRawEvents();
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
        String editorId = null; //Defined when an instruction is a data entry into tinyMCE.
        String path = null;
        String method = null;

        if(entity instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) entity;
            xpath = dataEntry.lastChange().getXpath();

            if (dataEntry.lastChange() instanceof TinymceEvent){
                editorId = ((TinymceEvent) dataEntry.lastChange()).getEditorId();
            }
        }

        if(entity instanceof ClickEvent){
            ClickEvent clickEvent = (ClickEvent) entity;
            xpath = clickEvent.getXpath();
        }

        if(entity instanceof CheckboxEvent){
            CheckboxEvent checkboxEvent = (CheckboxEvent) entity;
            xpath = checkboxEvent.xpath();
        }

        if(entity instanceof NetworkEvent){
            NetworkEvent networkEvent = (NetworkEvent) entity;
            path = networkEvent.getPath();
            method = networkEvent.getMethod();
        }

        if(entity instanceof ApplicationLocationChange){
            ApplicationLocationChange applicationLocationChange = (ApplicationLocationChange) entity;
            path = applicationLocationChange.getToPath();
        }

        final String observedXPath = xpath;
        final String observedPath = path;
        final String observedMethod = method;
        final String observedEditorId = editorId;

        if(observedXPath != null){
            log.info("Observed {} on xpath: {}", entity.symbol(), observedXPath);
        }

        if(observedPath != null){
            log.info("Observed {} with path: {}", entity.symbol(), observedPath);
        }

        if(observedMethod != null){
            log.info("Observed {} with method: {}", entity.symbol(), observedMethod);
        }

        if (observedEditorId != null){
            log.info("Observed {} with editor id: {}", entity.symbol(), observedEditorId);
        }

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

                    if (lastInstruction instanceof EnterDataTinymce){
                        if(observedEditorId == null){
                            return false;
                        }
                        return observedEditorId.equals(((EnterDataTinymce) lastInstruction).editorId);
                    }

                    if(lastInstruction instanceof XPathInstruction){
                        if(observedXPath == null){
                            return false;
                        }

                        //guidance.js cannot click on xpaths ending in 'svg', so it will click on the next closest element described in the xpath.
                        //Therefore, we have to register clicks on next-closest elements when the last instruction's xpath ends in 'svg'.
                        if(((XPathInstruction) lastInstruction).xpath.endsWith("/svg")){
                            String alsoValidXpath = (((XPathInstruction) lastInstruction).xpath).substring(0, ((XPathInstruction) lastInstruction).xpath.length() - "/svg".length());
                            log.info("alsoValidXpath: {}, observedXpath: {}", alsoValidXpath, observedXPath);
                            return observedXPath.equals(alsoValidXpath);
                        }

                        return observedXPath.equals(((XPathInstruction) lastInstruction).xpath);
                    }

                    if(lastInstruction instanceof DynamicXPathInstruction){
                        if(observedXPath == null){
                            return false;
                        }
                        log.info("Last instruction was dynamic xpath");
                        DynamicXPathInstruction dynamicXPathInstruction = (DynamicXPathInstruction) lastInstruction;
                        log.info("matches: {}, stillMatches: {}",dynamicXPathInstruction.dynamicXPath.matches(observedXPath), dynamicXPathInstruction.dynamicXPath.stillMatches(observedXPath) );
                        return dynamicXPathInstruction.dynamicXPath.matches(observedXPath) || dynamicXPathInstruction.dynamicXPath.stillMatches(observedXPath);
                    }

                    if(lastInstruction instanceof WaitForLocationChange){
                        return ((WaitForLocationChange) lastInstruction).path.equals(observedPath);
                    }

                    if(lastInstruction instanceof WaitForNetworkEvent){
                        return ((WaitForNetworkEvent) lastInstruction).path.equals(observedPath) && ((WaitForNetworkEvent) lastInstruction).method.equals(observedMethod);
                    }


                    log.warn("Unknown instruction type detected in navPath.lastInstruction()");
                    return false;
                })
                .collect(Collectors.toList())
        ;

        if(getActiveExecutionRequest() == null){
            log.info("No active execution request!");
        }

        //Track which nodes (excluding dom effects) we have visited so far.
        tempNavPaths.forEach(navPath -> getActiveExecutionRequest().getVisitedNodes().add(navPath.getLastInstructionNodeId()));

        if(tempNavPaths.size() > 0){
            this.navPaths = tempNavPaths;

            //Handle execution request TODO: refactor this
            JsonObject instruction = buildExecutionInstruction(tempNavPaths);
            if(instruction != null){
                client.getGuidanceConnectionManager()
                        .sendExecutionInstruction(instruction);
            }

        }else{
            //The observed event didn't match any of the events we'd expect to see along one of our current paths.
            if (entity instanceof NetworkEvent || entity instanceof ApplicationLocationChange) {
                //And the unmatched entity is a network or location change, then we re-compute paths to our target node.
                log.info("Observed an unexpected network event or an application location change!");

                Optional<UUID> updatedStartingNode = entity instanceof NetworkEvent?LogPreprocessor.localizer.findNodeByNetworkEvent((NetworkEvent) entity) : LogPreprocessor.localizer.findNodeByLocationChange((ApplicationLocationChange) entity);

                log.info("Does the unexpected network event or application location change exist in the nav model? {}", updatedStartingNode.isPresent());

                if(updatedStartingNode.isPresent()){
                    log.info("The unexpected network event or an application location change was found in the nav model! Re-computing paths...");

                    //Before we can recompute paths, we need to look through the object/input parameters associated with the request, and prune any that we've already encountered in the online timeline.
                    //Otherwise we'd look for paths that would try to revisit things we've already done. Or we won't find any paths because no paths exist that include the parameters in a way we'd expect.
                    //This approach is very limiting, especially for any kind of task that involves looping or revisiting nodes. We have to sit and think about path planning at some point.

                    ExecutionRequest request = getActiveExecutionRequest();
                    request.getInputParameters().removeAll(request.getVisitedNodes());
                    request.getObjectParameters().removeAll(request.getVisitedNodes());

                    tx.close(); //Close the previous graphdb transaction.
                    tx = LogPreprocessor.graphDB.db.beginTx();
                    navPaths = LogPreprocessor.pathsConstructor.construct(tx, updatedStartingNode.get().toString(), request.getObjectParameters(), request.getInputParameters(), request.getApiCalls());
                    request.addRecomputation();

                    log.info("Found {} paths after recomputation", navPaths.size());
                    navPaths = List.of(navPaths.get(0)); //Only return/use the first path for execution.

                    //Write the new path down for debugging
                    NavPath.saveNavPath("./%s/%s-navpath-%d.txt".formatted("execution_events", evalId, request.getPathRecomputations()).replaceAll("\\|","-"), navPaths.get(0));


                    /**
                     * We still need a target node so that the execution mechanism can determine when the task has been completed.
                     * All paths produced using the new path construction logic will end in an API node.
                     *
                     * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
                     * be our target node.
                     */
                    var targetNodeId = UUID.fromString(navPaths.get(0).getPath().endNode().getProperty("id").toString());
                    request.setTarget(targetNodeId);

                    JsonObject instruction = buildExecutionInstruction(navPaths);

                    Node firstNode = navPaths.get(0).getPath().startNode();

                    /**
                     * This method (getExecutionPath) is only called on to produce the first instruction for the execution.
                     * If the chosen path begins with a LocationNode (which is common), then the first instruction would
                     * be to wait for that location change. But since we likely just initialized our local context. There's
                     * not going to be an application location change event.
                     *
                     * To deal with this, if the execution instruction is null, as would be the case for WaitFor type instructions
                     * (because they don't send anything to OdoX, the instruction JSON is null), call buildExecutionInstruction again
                     * to get the next instruction.
                     *
                     * TODO: refactor this. This method is doing too much. And this 'temporary fix' only adds to the complexity of the execution logic.
                     */
                    if(instruction == null && (firstNode.hasLabel(Label.label("LocationNode")) || firstNode.hasLabel(Label.label("APINode")))) {
                        instruction = buildExecutionInstruction(navPaths);
                    }

                    if(instruction != null){
                        client.getGuidanceConnectionManager()
                                .sendExecutionInstruction(instruction);
                    }else{
                        log.error("Couldn't produce execution instruction for the re-computed path!");
                    }

                }



            }
        }
    }


}
