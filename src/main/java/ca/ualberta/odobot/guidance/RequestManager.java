package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.IntStream;

public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();

    private Request request = null;

    private PathsRequestInput _input = null;

    public RequestManager(Request request){
        this.request = request;
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

            List<NavPath> paths = LogPreprocessor.pathsConstructor.construct(src, tgt);

//            paths.sort(Comparator.comparingInt(navPath -> navPath.getPath().length()));
//
//            IntStream.range(0, paths.size())
//                    .limit(30)
//                    .forEach(i->{
//
//
//                                StringBuilder sb = new StringBuilder();
//                                paths.get(i).getPath().nodes().forEach(n->{
//                                    StringBuilder nsb = new StringBuilder();
//                                    nsb.append("(");
//                                    n.getLabels().forEach(label->nsb.append(":" + label.name()));
//                                    nsb.append("| id:%s)".formatted((String)n.getProperty("id")));
//                                    nsb.append("-->");
//                                    sb.append(nsb.toString());
//                                });
//
//                                log.info("Path[{}] length: {}: {}", i, paths.get(i).getPath().length(), sb.toString());
//
//                            });

            log.info("Found {} paths", paths.size());
        }catch (Exception e){
            log.error(e.getMessage(), e);
        }


        return Future.succeededFuture(new JsonObject()
                .put("navigationOptions", new JsonArray()
                        .add(new JsonObject().put("xpath", "//html/body/div[3]/div[2]/div/div/div[1]/div/div/div/div/div/div[2]/form[1]/div[3]/div[2]/button"))
                ));
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



}
