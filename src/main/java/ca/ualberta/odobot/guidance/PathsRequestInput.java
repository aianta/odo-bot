package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.jsoup.nodes.Document;

public class PathsRequestInput {
    Document dom;
    TimelineEntity lastEntity;
    String url;

    String pathRequestId;

    String targetNode;

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
