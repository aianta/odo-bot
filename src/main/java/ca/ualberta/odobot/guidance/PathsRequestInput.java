package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.jsoup.nodes.Document;

public class PathsRequestInput {
    Document dom;
    TimelineEntity lastEntity;
    /**
     * The url field is likely to be identical to the userLocation field. The difference is where they are sourced from.
     * The 'url' field is parsed from the local context. While the 'userLocation' field is submitted directly with the
     * paths requests. In situations where there is no local context, the url will be null, while the 'userLocation'
     * value should still be populated.
     */
    String url;

    String userLocation;

    String pathRequestId;

    String targetNode;

    public String getUserLocation() {
        return userLocation;
    }

    public void setUserLocation(String userLocation) {
        this.userLocation = userLocation;
    }

    public String getPathRequestId() {
        return pathRequestId;
    }

    public void setPathRequestId(String pathRequestId) {
        this.pathRequestId = pathRequestId;
    }

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
