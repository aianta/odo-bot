package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();

    private Request request = null;

    private PathsRequestInput _input = null;

    private class PathsRequestInput{
        Document dom;
        TimelineEntity lastEntity;
        String url;

        String targetNode;

        public String getTargetNode() {
            return targetNode;
        }

        public void setTargetNode(String targetNode) {
            this.targetNode = targetNode;
        }

        public Document getDom() {
            return dom;
        }

        public void setDom(Document dom) {
            this.dom = dom;
        }

        public TimelineEntity getLastEntity() {
            return lastEntity;
        }

        public void setLastEntity(TimelineEntity lastEntity) {
            this.lastEntity = lastEntity;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

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
        log.info("LastEntity: {}", _input.lastEntity.symbol());
        log.info("URL: {}", _input.url);
        log.info("DOM: {}", _input.dom.toString().substring(0, Math.min(_input.dom.toString().length(), 150)));
        log.info("TargetNode: {}", _input.targetNode);

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
