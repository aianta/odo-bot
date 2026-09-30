package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.execution.ExecutionParameter;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.feedback.AlternateXpath;
import ca.ualberta.odobot.guidance.feedback.UnresolvableXpath;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.semanticflow.navmodel.Localizer;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPathsConstructor;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.snippet2xml.Snippet2XMLService;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * The charted agent executes tasks by following paths through the navigation model.
 */
public class ChartedAgent extends AbstractAgent{

    private static final Logger log = LoggerFactory.getLogger(ChartedAgent.class);

    /**
     * The timeline entities that can advance a nav path.
     */
    private static final Predicate<TimelineEntity> TRACKED_ENTITIES = entity -> entity instanceof DataEntry || entity instanceof ClickEvent || entity instanceof CheckboxEvent || entity instanceof NetworkEvent || entity instanceof ApplicationLocationChange || entity instanceof SelectEvent || entity instanceof NoOpEvent;

    private ExecutionRequest.Type mode;
    private ExecutionRequest.PathSelectionMode pathSelectionMode;
    private Localizer localizer;
    private NavPathsConstructor pathsConstructor;
    private TaskPlannerService taskPlanner;
    private Snippet2XMLService snippet2XML;

    /**
     * True once the first observation has been received.
     */
    private boolean started = false;

    /**
     * Null until task interpretation has completed.
     */
    private ExecutionRequest request = null;

    private List<NavPath> navPaths = null;

    private Transaction tx;

    private final FollowUpContext followUpContext = new FollowUpContext() {
        @Override
        public TaskPlannerService taskPlanner() {
            return taskPlanner;
        }

        @Override
        public Snippet2XMLService snippet2XML() {
            return snippet2XML;
        }

        @Override
        public SqliteService sqlite() {
            return sqlite;
        }

        @Override
        public Neo4JUtils neo4J() {
            return neo4J;
        }

        @Override
        public ExecutionRequest request() {
            return request;
        }

        @Override
        public void replaceLastInstruction(Class<? extends Instruction> ifLastIs, Instruction with) {
            navPaths.forEach(path->{
                if(ifLastIs.isInstance(path.lastInstruction())){
                    path.updateLastInstruction(with);
                }
            });
        }

        @Override
        public void recoverFromFailedNode(String nodeId) {
            if(stopped){
                return;
            }
            ChartedAgent.this.recoverFromFailedNode(nodeId);
        }

        @Override
        public String artifactPath(String suffix) {
            return ChartedAgent.this.artifactPath(suffix);
        }
    };

    public static class Builder extends AbstractAgent.Builder<ChartedAgent, Builder>{

        ExecutionRequest.Type mode;
        ExecutionRequest.PathSelectionMode pathSelectionMode;
        Localizer localizer;
        NavPathsConstructor pathsConstructor;
        TaskPlannerService taskPlanner;
        Snippet2XMLService snippet2XML;

        public Builder mode(ExecutionRequest.Type mode){
            this.mode = mode;
            return this;
        }

        public Builder pathSelectionMode(ExecutionRequest.PathSelectionMode pathSelectionMode){
            this.pathSelectionMode = pathSelectionMode;
            return this;
        }

        public Builder localizer(Localizer localizer){
            this.localizer = localizer;
            return this;
        }

        public Builder pathsConstructor(NavPathsConstructor pathsConstructor){
            this.pathsConstructor = pathsConstructor;
            return this;
        }

        public Builder taskPlanner(TaskPlannerService taskPlanner){
            this.taskPlanner = taskPlanner;
            return this;
        }

        public Builder snippet2XML(Snippet2XMLService snippet2XML){
            this.snippet2XML = snippet2XML;
            return this;
        }

        @Override
        protected boolean validate() {
            return super.validate() && !(mode == null || pathSelectionMode == null || localizer == null || pathsConstructor == null || taskPlanner == null || snippet2XML == null);
        }

        @Override
        protected ChartedAgent create() {
            ChartedAgent chartedAgent = new ChartedAgent();
            chartedAgent.mode = mode;
            chartedAgent.pathSelectionMode = pathSelectionMode;
            chartedAgent.localizer = localizer;
            chartedAgent.pathsConstructor = pathsConstructor;
            chartedAgent.taskPlanner = taskPlanner;
            chartedAgent.snippet2XML = snippet2XML;
            return chartedAgent;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    @Override
    public void observationHandler(TimelineEntity timelineEntity) {

        if(timelineEntity instanceof Observation observation){
            onObservation(observation);
            return;
        }

        //Nothing to do until the task has been interpreted, or once the agent has been stopped.
        if(request == null || stopped){
            return;
        }

        if(timelineEntity instanceof UnresolvableXpath unresolvableXpath){
            recoverFromFailedNode(unresolvableXpath.sourceNodeId());
            return;
        }

        if(timelineEntity instanceof AlternateXpath alternateXpath){
            registerAlternateXpath(alternateXpath);
            return;
        }

        //No plan yet.
        if(navPaths == null){
            return;
        }

        if(TRACKED_ENTITIES.test(timelineEntity)){
            instructionWatcher(timelineEntity);
        }

        if(!stopped && timelineEntity instanceof NetworkEvent){
            pathCompletionWatcher(timelineEntity);
        }
    }

    @Override
    public void stop() {
        stopped = true;
        if(tx != null){
            tx.close();
        }
    }

    private void onObservation(Observation observation){

        if(!started){
            //First observation: interpret the task, then plan from the user's location.
            started = true;
            interpretTask()
                    .onFailure(err->{
                        log.error(err.getMessage(), err);
                        emit(new GiveUp(err.getMessage()));
                    })
                    .onSuccess(executionRequest->{
                        request = executionRequest;
                        plan(observation);
                    });
            return;
        }

        if(request == null){
            log.info("Ignoring observation, the task is still being interpreted.");
            return;
        }

        //Re-plan, this happens when control is handed back to this agent.
        stopped = false;
        plan(observation);
    }

    /**
     * Plans from the page the observation was made on. Falls back on the task's user location when the observation
     * carries none.
     */
    private void plan(Observation observation){
        String userLocation = observation.getUserLocation();
        if(userLocation == null){
            log.warn("Observation carries no user location, planning from the task's user location instead.");
            userLocation = request.getUserLocation();
        }

        getExecutionPath(request, userLocation)
                .onSuccess(instruction->{
                    if(instruction == null){
                        log.error("Error occurred while processing execution request");
                        log.error("Could not produce a first instruction for the execution path!");
                        return;
                    }
                    dispatch(instruction);
                })
                .onFailure(err->{
                    log.error("Error occurred while processing execution request");
                    log.error(err.getMessage(), err);
                });
    }

    /**
     * Hand an instruction to the harness.
     */
    private void dispatch(Instruction instruction){

        if(instruction instanceof MultiStepInstruction multiStepInstruction){
            multiStepInstruction.bind(followUpContext);
            emit(instruction);
            return;
        }

        //Handle execution-time data entry input resolution
        if(instruction instanceof EnterData enterData && enterData.data == null){
            log.info("Execution Instruction action for sourceNodeId: {} was 'input' with no defined data. Attempting real-time data entry resolution!", enterData.getSourceNodeId());
            taskPlanner.resolveDataEntryValue(
                    request.getTaskDescription(),
                    enterData.parameterId,
                    ""
                    )
                    .onFailure(err->log.error(err.getMessage(), err))
                    .onSuccess(inputData->{
                        log.info("Generated input field value: {} for instruction @ sourceNodeId: {}", inputData, enterData.getSourceNodeId());
                        emit(withData(enterData, inputData));
                    });
            return;
        }

        emit(instruction);
    }

    /**
     * @return a copy of the given data entry instruction with its data set, leaving the nav path's instruction untouched.
     */
    private EnterData withData(EnterData instruction, String data){
        EnterData result;
        if(instruction instanceof EnterDataTinymce tinymce){
            EnterDataTinymce _result = new EnterDataTinymce();
            _result.editorId = tinymce.editorId;
            result = _result;
        }else{
            result = new EnterData();
        }
        result.xpath = instruction.xpath;
        result.parameterId = instruction.parameterId;
        result.setSourceNodeId(instruction.getSourceNodeId());
        result.data = data;
        return result;
    }

    private Future<ExecutionRequest> interpretTask(){
        ExecutionRequest executionRequest = new ExecutionRequest();

        if(mode == ExecutionRequest.Type.NL){
            return taskPlanner.taskQueryConstructionV2(task)
                    .compose(definedTask->{
                        log.info("Got task definition from task query construction:\n{}", definedTask.encodePrettily());
                        saveTaskQueryConstructionResult("%s/%s-task-query-construction-result.json".formatted(this.artifactDir, definedTask.getString("_evalId")).replaceAll("\\|","-"), definedTask);

                        executionRequest.setTaskDescription(task.getString("task"));

                        executionRequest.setId(UUID.fromString(definedTask.getString("id")));
                        executionRequest.setUserLocation(definedTask.getString("userLocation"));
                        executionRequest.setType(ExecutionRequest.Type.NL);

                        assert definedTask.getJsonArray("targets").size() == 1;
                        String similarTaskId = definedTask.getJsonArray("targets").getJsonObject(0).getString("targetingTaskId");
                        executionRequest.setSimilarTaskId(similarTaskId);
                        executionRequest.setPathSelectionMode(this.pathSelectionMode);

                        JsonArray targets = definedTask.getJsonArray("targets");
                        executionRequest.setTargets(targets.stream()
                                .map(o->(JsonObject)o)
                                .map(o->o.getString("id"))
                                .collect(Collectors.toSet())
                        );

                        JsonArray parameters = definedTask.getJsonArray("parameters");
                        executionRequest.setParameters(parameters.stream()
                                .map(o->(JsonObject)o)
                                .map(ExecutionParameter::fromJson)
                                .collect(Collectors.toList())
                        );

                        return Future.succeededFuture(executionRequest);
                    });
        }

        if(mode == ExecutionRequest.Type.PREDEFINED){
            executionRequest.setId(UUID.fromString(task.getString("id")));
            executionRequest.setTarget(UUID.fromString(task.getJsonArray("targets").getJsonObject(0).getString("id")));
            executionRequest.setUserLocation(task.getString("userLocation"));
            executionRequest.setType(ExecutionRequest.Type.PREDEFINED);

            JsonArray parameters = task.getJsonArray("parameters");
            executionRequest.setParameters(parameters.stream()
                    .map(o->(JsonObject)o)
                    .map(ExecutionParameter::fromJson)
                    .collect(Collectors.toList())
            );

            JsonArray targets = task.getJsonArray("targets");
            executionRequest.setTargets(targets.stream()
                    .map(o->(JsonObject)o)
                    .map(o->o.getString("id"))
                    .collect(Collectors.toSet())
            );

            return Future.succeededFuture(executionRequest);
        }

        log.error("Unknown or unsupported agent type!");
        return Future.failedFuture("Unknown or unsupported agent type!");
    }

    private void saveTaskQueryConstructionResult(String filename, JsonObject result){
        File fout = new File(filename);
        try(FileWriter fw = new FileWriter(fout);
            BufferedWriter bw = new BufferedWriter(fw);
        ){

            bw.write(result.encodePrettily());
            bw.flush();

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    private void registerAlternateXpath(AlternateXpath alternateXpath){
        //During the execution of certain instructions, the exact xpath sent to OdoX by the server might differ from the one OdoX had to use to actually execute the instruction.
        //When this happens, we add the new xpath OdoX used to the corresponding instruction so that the instructionWatcher can properly identify that the expected instruciton was executed.
        //TODO: eventually, these new xpaths should be merged into the nav model.
        if(navPaths == null){
            return;
        }

        log.info("Attempting to register alternate xpath: {} for sourceNodeId: {}", alternateXpath.alternateXpath(), alternateXpath.sourceNodeId());
        Optional<Instruction> _instruction = navPaths.stream().map(NavPath::lastInstruction)
                .filter(lastInstruction -> lastInstruction.getSourceNodeId().equals(alternateXpath.sourceNodeId()))
                .findAny();

        if(_instruction.isPresent()){
            _instruction.get().addAlternateXpath(alternateXpath.alternateXpath());
            log.info("Successfully registered alternate xpath: {}", alternateXpath.alternateXpath());
        }
    }

    private Future<Instruction> getExecutionPath(ExecutionRequest request, String userLocation){

        try{
            log.info("User Location: {}", userLocation != null? userLocation: "N/A");

            Optional<UUID> startingNode = localizer.resolveStartingNode(userLocation);
            log.info("Found starting node? {}", startingNode.isPresent());

            UUID src = startingNode.get();

            //Handle Natural Language tasks
            if(request.getType() == ExecutionRequest.Type.NL){
                log.info("Natural language task request");
                //Resolve the parameter associated input and resource nodes
                //Basically the node IDs in the task definition correspond with the actual, Input and Resource parameter nodes.
                //What we actually want, are the ids of the nodes associated with those input and schema parameter nodes (as defined by the PARAM edge).
                Set<String> inputParameters = request.getParameters().stream()
                        .filter(p->p.getType().equals(ExecutionParameter.ParameterType.InputParameter))
                        .map(p->neo4J.getParameterAssociatedNodes(p.getNodeId().toString()))
                        .collect(HashSet::new, HashSet::addAll, HashSet::addAll);

                Set<String> resourceParameters = request.getParameters().stream()
                        .peek(param->log.info("[1]Parameter: {}", param.toJson().encodePrettily() ))
                        .filter(p->p.getType().equals(ExecutionParameter.ParameterType.ResourceParameter))
                        .peek(param->log.info("[2]Parameter: {}", param.toJson().encodePrettily() ))
                        .map(p->neo4J.getParameterAssociatedNodes(p.getNodeId().toString()))
                        .peek(param->log.info("[3]Parameter Id: {}", param ))
                        .collect(HashSet::new, HashSet::addAll,HashSet::addAll);

                log.info("Resource Parameter Set in getExecutionPath: {}", resourceParameters.toString());

                Set<String> apiCalls = request.getTargets();

                //Save these for later if we need to re-compute paths for this request.
                request.setApiCalls(apiCalls);
                request.setInputParameters(inputParameters);
                request.setResourceParameters(resourceParameters);

                tx = graphDB.db.beginTx();

                //navPaths = pathsConstructor.construct(tx, src.toString(), resourceParameters, inputParameters, apiCalls);
                //navPaths = pathsConstructor.constructV2(tx, src.toString(), resourceParameters, inputParameters, apiCalls);
                navPaths = pathsConstructor.constructV3(tx, src.toString(), resourceParameters, inputParameters, apiCalls);

                //First collect together our parameter mappings, we'll need this to generate semantically meaningful natural language descriptions of the different paths.
                JsonArray parameters = request.getParameterAsJson();

                //Different nav paths only matter if they involve different interactions.
                //We can determine if they actually have unique sets of interactions by converting them to natural language and ensuring the uniqueness of the output.
                Set<String> uniqueNavPaths = new HashSet<>();
                Iterator<NavPath> pathIt = navPaths.iterator();
                while (pathIt.hasNext()){
                    NavPath p = pathIt.next();
                    JsonArray nlPath = p.toNaturalLanguage().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
                    int prevSize = uniqueNavPaths.size();
                    uniqueNavPaths.add(nlPath.encode());
                    if(prevSize == uniqueNavPaths.size()){
                        pathIt.remove();
                    }
                }

                if (navPaths.isEmpty()){
                    emit(new GiveUp("No paths for task execution."));
                }

                log.info("Found {} execution paths", navPaths.size());
                if(navPaths.size() > 1){

                    return this.naturalLanguagePathSelection(navPaths, request)
                            .compose(chosenPath->{
                                navPaths = List.of(chosenPath);

                                Instruction executionInstruction = buildExecutionInstruction(navPaths);
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
                NavPath.saveNavPath(artifactPath("navpath.txt"), navPaths.get(0));

                /**
                 * We still need a target node so that the execution mechanism can determine when the task has been completed.
                 * All paths produced using the new path construction logic will end in an API node.
                 *
                 * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
                 * be our target node.
                 */
                var targetNodeId = UUID.fromString(navPaths.get(0).getPath().endNode().getProperty("id").toString());
                request.setTarget(targetNodeId);

                log.info("Found {} execution paths", navPaths.size());
            }

            //Handle tasks that have been pre-defined in terms of the navigational model.
            if(request.getType() == ExecutionRequest.Type.PREDEFINED){
                log.info("TargetNode: {}", request.getTarget());

                UUID tgt = request.getTarget();

                log.info("Resolving execution path from {} to {}", src.toString(), tgt.toString());

                tx = graphDB.db.beginTx();

                //Resolve the parameter associated input and resource nodes
                //Basically the node IDs in the task definition correspond with the actual, Input and Resource parameter nodes.
                //What we actually want, are the ids of the nodes associated with those input and schema parameter nodes (as defined by the PARAM edge).
                Set<String> inputParameters = request.getParameters().stream()
                        .filter(p->p.getType().equals(ExecutionParameter.ParameterType.InputParameter))
                        .map(p->neo4J.getParameterAssociatedNodes(p.getNodeId().toString()))
                        .collect(HashSet::new, HashSet::addAll, HashSet::addAll);

                Set<String> resourceParameters = request.getParameters().stream()
                        .peek(param->log.info("[1]Parameter: {}", param.toJson().encodePrettily() ))
                        .filter(p->p.getType().equals(ExecutionParameter.ParameterType.ResourceParameter))
                        .peek(param->log.info("[2]Parameter: {}", param.toJson().encodePrettily() ))
                        .map(p->neo4J.getParameterAssociatedNodes(p.getNodeId().toString()))
                        .peek(param->log.info("[3]Parameter Id: {}", param ))
                        .collect(HashSet::new, HashSet::addAll,HashSet::addAll);

                Set<String> apiCalls = request.getTargets();

                //Save these for later if we need to re-compute paths for this request.
                request.setApiCalls(apiCalls);
                request.setInputParameters(inputParameters);
                request.setResourceParameters(resourceParameters);

                navPaths = pathsConstructor.constructV3(tx, src.toString(), resourceParameters, inputParameters, apiCalls);

                //First collect together our parameter mappings, we'll need this to generate semantically meaningful natural language descriptions of the different paths.
                JsonArray parameters = request.getParameters().stream().map(ExecutionParameter::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                //Different nav paths only matter if they involve different interactions.
                //We can determine if they actually have unique sets of interactions by converting them to natural language and ensuring the uniqueness of the output.
                Set<String> uniqueNavPaths = new HashSet<>();
                Iterator<NavPath> pathIt = navPaths.iterator();
                while (pathIt.hasNext()){
                    NavPath p = pathIt.next();
                    JsonArray nlPath = p.toNaturalLanguage().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
                    int prevSize = uniqueNavPaths.size();
                    uniqueNavPaths.add(nlPath.encode());
                    if(prevSize == uniqueNavPaths.size()){
                        pathIt.remove();
                    }
                }

                log.info("Found {} execution paths", navPaths.size());

                if(navPaths.size() > 1){

                    return this.naturalLanguagePathSelection(navPaths, request)
                            .compose(chosenPath->{
                                navPaths = List.of(chosenPath);

                                Instruction executionInstruction = buildExecutionInstruction(navPaths);
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
                NavPath.saveNavPath(artifactPath("navpath.txt"), navPaths.get(0));

                /**
                 * We still need a target node so that the execution mechanism can determine when the task has been completed.
                 * All paths produced using the new path construction logic will end in an API node.
                 *
                 * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
                 * be our target node.
                 */
                var targetNodeId = UUID.fromString(navPaths.get(0).getPath().endNode().getProperty("id").toString());
                request.setTarget(targetNodeId);

                log.info("Found {} execution paths", navPaths.size());

            }

            Instruction executionInstruction = buildExecutionInstruction(navPaths);
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


    private Instruction buildExecutionInstruction(List<NavPath> paths){

        /**
         * We want to get distinct execution instructions from our set of possible paths. In fact, we have to reduce things to a single possible instruction.
         */
        List<Instruction> possibleExecutionInstructions = paths.stream()
                .peek(navPath -> NavPath.printNavPaths(List.of(navPath),100))
                .map(navPath -> navPath.getExecutionInstruction(request))

                .filter(Objects::nonNull)
                .distinct()
                .peek(instruction -> {log.info("{}", instruction.getClass().getName());

                    if(instruction instanceof WaitForLocationChange){
                        log.info("Waiting for location to change to: {}", ((WaitForLocationChange) instruction).path);
                    }

                })
                .map(instruction -> {

                    //TODO: hmmmm, refactor this.
                    //Expect to return null for WaitForLocationChange and WaitForNetworkEvent instructions.
                    //This is because they do not entail sending any instructions to OdoX. Rather, they are instructions for the server
                    //to wait for the specified interactions to take place in the online timeline.
                    if(instruction instanceof WaitForLocationChange || instruction instanceof WaitForNetworkEvent){
                        return null;
                    }

                    return instruction;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
        ;

        log.info("Computed {} possible execution instructions, sending the first one!", possibleExecutionInstructions.size());

        //No instructions left, return null.
        if(possibleExecutionInstructions.size() == 0){
            return null;
        }

        Instruction executionInstruction = possibleExecutionInstructions.get(0);
        log.info("{}", executionInstruction.toJson().encodePrettily());

        return executionInstruction;
    }


    /**
     * This method checks if a given timeline entity matches corresponds with the specified target node for the active request.
     *
     * @param entity
     */
    private void pathCompletionWatcher(TimelineEntity entity){


        NetworkEvent apiCall = (NetworkEvent) entity; //Note: When we set up this watcher we ensure it only receives network events.



        //Define conditions under which the observed network event matches the target of the execution request.
        Predicate<NetworkEvent> networkEventMatchesTarget = (networkEvent)->{
            //Handle the case where the target is a graphQL operation
            Optional<String> graphQL = networkEvent.getGraphQLOperationName();
            if(graphQL.isPresent() && request.getTargetOperationName() != null && request.getTargetOperationName().equals(graphQL.get())){
                return true;
            }else if (request.getTargetOperationName() == null){
                //Handle the case where the target is a 'normal' API call.
                return networkEvent.getMethod().toLowerCase().equals(request.getTargetMethod().toLowerCase()) && //If the method matches
                        networkEvent.getPath().equals(request.getTargetPath());
            }

            return false;
        };

        if(networkEventMatchesTarget.test(apiCall)){
            emit(new TaskComplete());
        }



    }

    private void recoverFromFailedNode(String failedNodeId){

        Optional<NavPath> currentPath = navPaths.stream().filter(navPath -> navPath.lastInstruction().getSourceNodeId().equals(failedNodeId)).findAny();
        if(currentPath.isPresent()){

            NavPath _currentPath = currentPath.get();

            //Update the execution request, registering the failed node.
            request.addFailedNode(failedNodeId);

            Optional<String> updatedStartingNodeId = _currentPath.getRecoverableStartingNodeId(failedNodeId);

            //If we found a new node to recompute a path from, let's do so.
            if(updatedStartingNodeId.isPresent()){

                log.info("Possible recovery starting from node: {}",  updatedStartingNodeId.get());

                ExecutionRequest request = this.request;
                request.getInputParameters().removeAll(request.getVisitedNodes());
                request.getResourceParameters().removeAll(request.getVisitedNodes());
                request.addFailedNode(failedNodeId);

                tx.close(); //Close the previous graphDb transaction.
                tx = graphDB.db.beginTx(); // Open a new one

                //navPaths = pathsConstructor.constructV2(tx, updatedStartingNodeId.get(), request.getResourceParameters(), request.getInputParameters(), request.getApiCalls(), request.getFailedNodes());
                navPaths = pathsConstructor.constructV3(tx, updatedStartingNodeId.get(), request.getResourceParameters(), request.getInputParameters(), request.getApiCalls(), request.getFailedNodes());
                request.addRecomputation();

                log.info("Found {} paths after recomputation", navPaths.size());

                if(navPaths.size() > 1){

                    this.naturalLanguagePathSelection(navPaths, request)
                            .onSuccess(chosenPath->{
                                navPaths = List.of(chosenPath);

                                Instruction instruction =  buildExecutionInstruction(navPaths);

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

                                //We don't want to execute a previously executed instruction twice. So get the next instruction here.
                                //Basically, if we recovered from a DataEntry or Click node, the new path would first attempt to re-execute that instruction. So we want to skip that and move to the next instruction along the new path.
                                if(instruction.getSourceNodeId().equals(updatedStartingNodeId.get())){
                                    instruction = buildExecutionInstruction(navPaths);
                                }

                                if(instruction != null){
                                    dispatch(instruction);
                                }else{
                                    log.error("Could not produce execution instruction for the re-computed path!");
                                }
                            })
                            .onFailure(err->log.error(err.getMessage(),err));

                }else{
                    if(navPaths.isEmpty()){
                        emit(new GiveUp("Could not find new nav path in recovery attempt!"));
                        return;
                    }
                    navPaths = List.of(navPaths.get(0));

                    NavPath.saveNavPath(artifactPath("navpath-%d.txt".formatted(request.getPathRecomputations())), navPaths.get(0));

                    var targetNodeId = UUID.fromString(navPaths.get(0).getPath().endNode().getProperty("id").toString());
                    request.setTarget(targetNodeId);

                    Instruction instruction =  buildExecutionInstruction(navPaths);

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

                    //We don't want to execute a previously executed instruction twice. So get the next instruction here.
                    //Basically, if we recovered from a DataEntry or Click node, the new path would first attempt to re-execute that instruction. So we want to skip that and move to the next instruction along the new path.
                    if(instruction.getSourceNodeId().equals(updatedStartingNodeId.get())){
                        instruction = buildExecutionInstruction(navPaths);
                    }

                    if(instruction != null){
                        dispatch(instruction);
                    }else{
                        log.error("Could not produce execution instruction for the re-computed path!");
                    }
                }


            }


        }
    }

    /**
     * This method checks if a given timeline entity matches an instruction that was given to the user. If so, it computes the next instruction
     * to give to the user.
     *
     * @param entity
     */
    private void instructionWatcher(TimelineEntity entity){
        log.info("instructionWatcher observed entity: {}", entity.symbol());

        if(navPaths == null){ // If there are no navigation paths currently being managed for this request, then we can ignore realtime events.
            return;
        }

        String xpath = null;
        String editorId = null; //Defined when an instruction is a data entry into tinyMCE.
        String path = null;
        String method = null;
        String operationName = null;

        if(entity instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) entity;
            xpath = dataEntry.lastChange().getXpath();

            if (dataEntry.lastChange() instanceof TinymceEvent){
                editorId = ((TinymceEvent) dataEntry.lastChange()).getEditorId();
            }
        }

        if (entity instanceof SelectEvent selectEvent){
            xpath = selectEvent.getXpath();
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

            Optional<String> opName = networkEvent.getGraphQLOperationName();
            if(opName.isPresent()){
                operationName = opName.get();
            }
        }

        if(entity instanceof ApplicationLocationChange){
            ApplicationLocationChange applicationLocationChange = (ApplicationLocationChange) entity;
            path = applicationLocationChange.getToPath();
        }

        final String observedXPath = xpath;
        final String observedPath = path;
        final String observedOperationName = operationName;
        final String observedMethod = method;
        final String observedEditorId = editorId;

        if(observedXPath != null){
            log.info("Observed {} on xpath: {}", entity.symbol(), observedXPath);
        }

        if(observedOperationName != null){
            log.info("Observed {} with operation name: {}", entity.symbol(), observedOperationName);
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

        navPaths.stream().forEach(np->log.info("Last Instruction: {}", np.lastInstruction()));

        /**
         * Update the navPaths associated with this request.
         * Prune all paths whose last instruction was not followed by the user.
         */
        List<NavPath> tempNavPaths = navPaths.stream()
                .filter(navPath -> {
                    Instruction lastInstruction = navPath.lastInstruction();

                    if(lastInstruction == null){
                        log.error("Last instruction is null!");
                        emit(new TaskComplete());
                        //throw new RuntimeException("Last instruction was null!");
                    }

                    if(lastInstruction instanceof NoOp){
                        return true;
                    }

                    if (lastInstruction instanceof EnterDataTinymce){
                        if(observedEditorId == null){
                            return false;
                        }

                        //April 22, 2026: So long as we observed an editor ID, we'll consider it a match. The editor ids in our trace can be instance specific, so we can't necessarily rely on them matching exactly. This is a bit of a hack, but it allows us to move forward with the execution logic while we figure out a better way to handle this.
                        return observedEditorId != null;
                        //return observedEditorId.equals(((EnterDataTinymce) lastInstruction).editorId);
                    }

                    if(lastInstruction instanceof XPathInstruction){

                        //If the observed entity is a data entry, but the last instruction was not an EnterData instruction, then this isn't a match.
                        //The situation can occur, in particular with tinyMCE widgets, which, when they contain existing content, may emit data entry events during their initalization.
                        if (entity instanceof DataEntry && !(lastInstruction instanceof EnterData)){
                            return false;
                        }

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


                        log.info("There are {} alternate xpaths associated with the last instruction", lastInstruction.alternateXpaths().size());
                        lastInstruction.alternateXpaths().forEach(alternateXpath->log.info("{}", alternateXpath));

                        log.info("Expected outcome: {}", observedXPath.equals(((XPathInstruction) lastInstruction).xpath) || lastInstruction.alternateXpaths().contains(observedXPath));
                        log.info("lastInstruction.alternateXpaths().contains(observedPath): {}", lastInstruction.alternateXpaths().contains(observedXPath));

                        return observedXPath.equals(((XPathInstruction) lastInstruction).xpath) || lastInstruction.alternateXpaths().contains(observedXPath);
                    }

                    if(lastInstruction instanceof DynamicXPathInstruction){
                        if(observedXPath == null){
                            return false;
                        }
                        log.info("Last instruction was dynamic xpath");
                        DynamicXPathInstruction dynamicXPathInstruction = (DynamicXPathInstruction) lastInstruction;

                        log.info("Last instruction alternateXpath size: {}", lastInstruction.alternateXpaths().size());
                        if(!lastInstruction.alternateXpaths().isEmpty()){
                            log.info("Last instruction alternateXpath: {}", lastInstruction.alternateXpaths().iterator().next());
                        }
                        //If the last instruction was a dynamic xpath instruction which was resolved but with an alternate xpath, report a match, so long as the observed xpath is in the list of registered alternate xpaths.
                        if(!lastInstruction.alternateXpaths().isEmpty()){
                            return lastInstruction.alternateXpaths().contains(observedXPath);
                        }

                        //Handle query dom instructions with multiple dynamic xpaths
                        if(dynamicXPathInstruction instanceof QueryDom && ((QueryDom)dynamicXPathInstruction).dynamicXPaths != null && !((QueryDom)dynamicXPathInstruction).dynamicXPaths.isEmpty()){
                            QueryDom queryDom = (QueryDom) dynamicXPathInstruction;



                            //As long as any match or still match we have a hit.
                            return queryDom.dynamicXPaths.stream().anyMatch(dynamicXPath -> dynamicXPath.matches(observedXPath) || dynamicXPath.stillMatches(observedXPath))
                                    ||
                                    queryDom.alternateXpaths().contains(observedXPath);


                        }

                        log.info("matches: {}, stillMatches: {}",dynamicXPathInstruction.dynamicXPath.matches(observedXPath), dynamicXPathInstruction.dynamicXPath.stillMatches(observedXPath) );
                        return dynamicXPathInstruction.dynamicXPath.matches(observedXPath) || dynamicXPathInstruction.dynamicXPath.stillMatches(observedXPath);
                    }

                    if(lastInstruction instanceof GetDOMSnapshot){
                        //TODO: Verify that the observed click's href matches one of the normalized hrefs for the Resource parameter label.
                        //For now, this allows any subsequent click event to validate against the GetDOMSnapshot instruction.
                        //return observedPath != null;
                        return true;
                    }

                    if(lastInstruction instanceof WaitForLocationChange){
                        return ((WaitForLocationChange) lastInstruction).path.equals(observedPath);
                    }

                    if(lastInstruction instanceof WaitForNetworkEvent){
                        WaitForNetworkEvent  waitForNetworkEvent = (WaitForNetworkEvent) lastInstruction;
                        //Handle graphQLNode case
                        if (waitForNetworkEvent.operationName != null) {
                            return waitForNetworkEvent.operationName.equals(observedOperationName);
                        }

                        return ((WaitForNetworkEvent) lastInstruction).path.equals(observedPath) && ((WaitForNetworkEvent) lastInstruction).method.equals(observedMethod);
                    }


                    log.warn("Unknown instruction type detected in navPath.lastInstruction()");
                    return false;
                })
                .collect(Collectors.toList())
        ;

        //The task completes while filtering if a path has no instructions left, in which case there is nothing more to do.
        if(stopped){
            return;
        }

        if(request == null){
            log.info("No active execution request!");
        }

        //Track which nodes (excluding dom effects) we have visited so far.
        tempNavPaths.forEach(navPath -> request.getVisitedNodes().add(navPath.getLastInstructionNodeId()));

        if(tempNavPaths.size() > 0){
            this.navPaths = tempNavPaths;

            //Handle execution request TODO: refactor this
            Instruction instruction = buildExecutionInstruction(tempNavPaths);
            if(instruction != null){
                dispatch(instruction);
            }

        }else{
            //The observed event didn't match any of the events we'd expect to see along one of our current paths.
            if ( entity instanceof ApplicationLocationChange) {
                //And the unmatched entity is a network or location change, then we re-compute paths to our target node.
                log.info("Observed an unexpected network event or an application location change!");

                Optional<UUID> updatedStartingNode = entity instanceof NetworkEvent? localizer.findNodeByNetworkEvent((NetworkEvent) entity) : localizer.findNodeByLocationChange((ApplicationLocationChange) entity);

                log.info("Does the unexpected network event or application location change exist in the nav model? {}", updatedStartingNode.isPresent());

                if(updatedStartingNode.isPresent()){
                    log.info("The unexpected network event or an application location change was found in the nav model! Re-computing paths...");

                    //Before we can recompute paths, we need to look through the object/input parameters associated with the request, and prune any that we've already encountered in the online timeline.
                    //Otherwise we'd look for paths that would try to revisit things we've already done. Or we won't find any paths because no paths exist that include the parameters in a way we'd expect.
                    //This approach is very limiting, especially for any kind of task that involves looping or revisiting nodes. We have to sit and think about path planning at some point.

                    ExecutionRequest request = this.request;
                    request.getInputParameters().removeAll(request.getVisitedNodes());
                    request.getResourceParameters().removeAll(request.getVisitedNodes());

                    tx.close(); //Close the previous graphdb transaction.
                    tx = graphDB.db.beginTx();

                    //TODO - path planning/finding work
                    //navPaths = pathsConstructor.construct(tx, updatedStartingNode.get().toString(), request.getObjectParameters(), request.getInputParameters(), request.getApiCalls());
                    //navPaths = pathsConstructor.constructV2(tx, updatedStartingNode.get().toString(), request.getResourceParameters(), request.getInputParameters(), request.getApiCalls());
                    navPaths = pathsConstructor.constructV3(tx, updatedStartingNode.get().toString(), request.getResourceParameters(), request.getInputParameters(), request.getApiCalls());
                    request.addRecomputation();

                    log.info("Found {} paths after recomputation", navPaths.size());

                    if(navPaths.size() > 1){
                        this.naturalLanguagePathSelection(navPaths, request)
                                .onSuccess(chosenPath->{
                                    navPaths = List.of(chosenPath);

                                    Instruction instruction = buildExecutionInstruction(navPaths);

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
                                        dispatch(instruction);
                                    }else{
                                        log.error("Couldn't produce execution instruction for the re-computed path!");
                                    }
                                })
                                .onFailure(err->log.error(err.getMessage(), err));

                    }else{
                        if (navPaths.isEmpty()){
                            emit(new GiveUp("Could not find nav path in recovery attempt!"));
                            return;
                        }
                        navPaths = List.of(navPaths.get(0)); //Only return/use the first path for execution.

                        //Write the new path down for debugging
                        NavPath.saveNavPath(artifactPath("navpath-%d.txt".formatted(request.getPathRecomputations())), navPaths.get(0));


                        /**
                         * We still need a target node so that the execution mechanism can determine when the task has been completed.
                         * All paths produced using the new path construction logic will end in an API node.
                         *
                         * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
                         * be our target node.
                         */
                        var targetNodeId = UUID.fromString(navPaths.get(0).getPath().endNode().getProperty("id").toString());
                        request.setTarget(targetNodeId);

                        Instruction instruction = buildExecutionInstruction(navPaths);

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
                            dispatch(instruction);
                        }else{
                            log.error("Couldn't produce execution instruction for the re-computed path!");
                        }
                    }



                }



            }
        }
    }



    private Future<NavPath> naturalLanguagePathSelection(List<NavPath> navPaths, ExecutionRequest request){

        switch (request.getPathSelectionMode()){
            //In heuristic mode, the path whose nodes most overlap with the selected similar task is used. In case of ties the first entry is chosen.
            case HEURISTIC:
                //First, compute how many vertices the selected high-level trajectory covers from the given paths, and include this information in the path selection context.
                return sqlite.getModelNodeIdsForTrajectory(request.getSimilarTaskId())
                        .onFailure(err->log.error(err.getMessage(), err))
                        .compose(nodeIdsForTrajectory->{
                            LinkedHashMap<NavPath, Long> pathCoverageMap =
                                    navPaths.stream()
                                        .map(navPath->Map.entry(navPath, navPath.getPathNodeIds().stream().filter(nodeIdsForTrajectory::contains).count()))
                                        .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1,e2)->e2, LinkedHashMap::new));


                            return !pathCoverageMap.isEmpty()?Future.succeededFuture(pathCoverageMap.entrySet().iterator().next().getKey()):Future.failedFuture("No path coverage entries!");
                        })
                ;
            case HYBRID:
                //Compute the coverage map for the chosen similar task, and include that information when asking the LLM to select the path.
                return Future.all(
                        sqlite.getModelNodeIdsForTrajectory(request.getSimilarTaskId()),
                        sqlite.getTaskDescription(request.getSimilarTaskId())
                )
                        .onFailure(err->log.error(err.getMessage(), err))
                        .compose(compositeFuture->{
                            Set<String> nodeIdsForTrajectory = compositeFuture.resultAt(0);
                            String similarTaskDescription =  compositeFuture.resultAt(1);

                            LinkedHashMap<NavPath, Long> pathCoverageMap =
                                    navPaths.stream()
                                            .map(navPath->Map.entry(navPath, navPath.getPathNodeIds().stream().filter(nodeIdsForTrajectory::contains).count()))
                                            .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1,e2)->e2, LinkedHashMap::new));

                            JsonObject paths = new JsonObject();
                            pathCoverageMap.entrySet().stream().forEach(entry->{
                                NavPath navPath = entry.getKey();
                                long coverage = entry.getValue(); //How many vertices of this navpath does the similar trajectory cover?
                                paths.put(navPath.getId().toString(), new JsonObject()
                                        .put("coverage", coverage)
                                                .put("length", navPath.getPath().length())
                                        .put("steps", navPath.toNaturalLanguage().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                                );
                            });

                            return taskPlanner.selectPath(paths, request.getTaskDescription(), similarTaskDescription)
                                    .onFailure(err->log.error(err.getMessage(), err))
                                    .compose(chosenPathInfo->handleChosenPathResult(chosenPathInfo, request));



                        });

            case LLM:
                //Have the LLM decide entirely by itself which path best fits the task description.
                //Now we prompt the LLM to decide between the paths we were able to find. This is where/how the system decides between create/edit paths for example.

                //Create a JsonObject containing all the different path options.
                //Each entry in the object is going to be <navPathID> : <Natural language steps in JsonArray>
                JsonObject paths = new JsonObject();

                navPaths.forEach(navPath -> paths.put(navPath.getId().toString(), navPath.toNaturalLanguage().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll)));
                return taskPlanner.selectPath(paths, request.getTaskDescription(), null)
                        .onFailure(err->log.error(err.getMessage(), err))
                        .compose(chosenPathInfo->handleChosenPathResult(chosenPathInfo, request));


            default:
                return Future.failedFuture("Unknown path selection mode!");
        }

    }

    private Future<NavPath> handleChosenPathResult(JsonObject chosenPathInfo, ExecutionRequest request){
        String chosenPathId = chosenPathInfo.getString("chosenPathId");
        TaskPlannerService.saveChosenPathTelemetry(artifactPath("path-selection-result-%d.txt".formatted(request.getPathRecomputations())), chosenPathInfo);
        Optional<NavPath> _chosenPath = navPaths.stream().filter(navPath->navPath.getId().equals(UUID.fromString(chosenPathId))).findFirst();
        if(_chosenPath.isPresent()){
            NavPath chosenPath = _chosenPath.get();
            NavPath.saveNavPath(artifactPath("navpath-%d.txt".formatted(request.getPathRecomputations())), chosenPath);

            /**
             * We still need a target node so that the execution mechanism can determine when the task has been completed.
             * All paths produced using the new path construction logic will end in an API node.
             *
             * I think, in practice, we ultimately end up following the first path's instructions. So the last node in the first path should effectively
             * be our target node.
             */
            var targetNodeId = UUID.fromString(chosenPath.getPath().endNode().getProperty("id").toString());
            request.setTarget(targetNodeId);

            return Future.succeededFuture(chosenPath);
        }else{
            return Future.failedFuture("No chosen path was present!");
        }
    }

}
