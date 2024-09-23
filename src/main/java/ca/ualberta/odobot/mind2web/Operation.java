package ca.ualberta.odobot.mind2web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
public class Operation {

    private static final Logger log = LoggerFactory.getLogger(Operation.class);

    protected String actionId;

    protected String rawHTML;

    protected String targetElementXpath;

    public String getTargetElementXpath() {
        return targetElementXpath;
    }

    public void setTargetElementXpath(String targetElementXpath) {
        this.targetElementXpath = targetElementXpath;
    }

    public String getActionId() {
        return actionId;
    }

    public Operation setActionId(String actionId) {
        this.actionId = actionId;
        return this;
    }

    public String getRawHTML() {
        return rawHTML;
    }

    public Operation setRawHTML(String rawHTML) {
        this.rawHTML = rawHTML;
        return this;
    }

    public Element targetElement(){

        if(targetElementXpath == null || targetElementXpath.isEmpty() || targetElementXpath.isBlank()){
            log.error("targetElementXpath is not defined for action {}, cannot retrieve target element!", actionId);
            return null;
        }

        Document document = Jsoup.parse(rawHTML);
        log.info("Retrieving element @: {}", this.getTargetElementXpath());
        return document.selectXpath(this.targetElementXpath).get(0);
    }
}
