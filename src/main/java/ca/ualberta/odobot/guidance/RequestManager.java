package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.DynamicXPathInstruction;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.guidance.instructions.XPathInstruction;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();

    private Request request = null;

    private List<NavPath> navPaths = null;

    private PathsRequestInput _input = null;

    private Transaction tx;

    public RequestManager(Request request){
        this.request = request;
        this.request.setRequestManager(this);
        this.request.getControlConnectionManager().setNewRequestTargetNodeConsumer(this::handleNewPathsRequest);
    }

    public void handleNewPathsRequest(String targetNode){

        request.getEventConnectionManager().getLocalContext()
                .compose(localContext->getPaths(targetNode, localContext))
                .compose(navigationOptions->request.getGuidanceConnectionManager().showNavigationOptions(navigationOptions))
                .onSuccess(navigationOptionsShown->request.getEventConnectionManager().startTransmitting());
        ;


    }

    public Future<JsonObject> getPaths(String targetNode, JsonArray localContext){

        log.info("Processing local context into PathsRequestInput");

        //TODO: Should we really only be looking at DataEntries and ClickEvents, even for sourcing the last DOM and URL?
        eventProcessor.setOnEntity( this::buildPathsRequestInput , (entity)->entity instanceof DataEntry || entity instanceof ClickEvent);
        eventProcessor.process(localContext);

        _input.setTargetNode(targetNode);
        _input.setPathRequestId(request.id().toString());
        log.info("LastEntity: {}", _input.lastEntity.symbol());
        log.info("URL: {}", _input.url);
        log.info("DOM: {}", _input.dom.toString().substring(0, Math.min(_input.dom.toString().length(), 150)));
        log.info("TargetNode: {}", _input.targetNode);

        try{
            Optional<UUID> startingNode = LogPreprocessor.localizer.resolveStartingNode(_input);
            log.info("Found starting node? {}", startingNode.isPresent());

            UUID src = startingNode.get();
            UUID tgt = UUID.fromString(targetNode);

            log.info("Resolving path from {} to {}", src.toString(), tgt.toString());

            tx = LogPreprocessor.graphDB.db.beginTx();

            navPaths = LogPreprocessor.pathsConstructor.construct(tx, src, tgt);

            buildNavigationOptions(navPaths);

            log.info("Found {} paths", navPaths.size());
        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return Future.succeededFuture(new JsonObject().put("navigationOptions", buildNavigationOptions(navPaths)));
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
                        return new JsonObject().put("xpath", xPathInstruction.xpath);
                    }

                    if(instruction instanceof DynamicXPathInstruction){
                        DynamicXPathInstruction dynamicXPathInstruction = (DynamicXPathInstruction) instruction;
                        return new JsonObject().put("xpath", dynamicXPathInstruction.dynamicXPath.toJson());
                    }

                    return null;
                })
                //Filter out any nulls, and collect it all into a JsonArray
                .filter(Objects::nonNull)
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);


        log.info("Produced {} instructions/unique navigation options.", navOptions.size());

        return navOptions;

    }

    public void entityWatcher(TimelineEntity entity){

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
            request.getGuidanceConnectionManager().showNavigationOptions(
                    new JsonObject().put("navigationOptions", buildNavigationOptions(this.navPaths))
            );
        }



    }


}
