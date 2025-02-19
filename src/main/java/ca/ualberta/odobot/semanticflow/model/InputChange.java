package ca.ualberta.odobot.semanticflow.model;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InputChange extends AbstractArtifact{

    private static final Logger log = LoggerFactory.getLogger(InputChange.class);
    private Element inputElement;
    private String value;
    private String placeholderText;

    private String outerHTML;

    public boolean isCheckbox(){
        if(this.outerHTML == null){
            log.warn("Cannot verify if input change represents a checkbox interaction because this.outerHTML is null.");
            throw new RuntimeException("Cannot verify if input change represents a checkbox interaction because this.outerHTML is null.");
        }
        Document document = Jsoup.parse(this.outerHTML);
        return document.body().firstElementChild().attributes().get("type").equals("checkbox");
    }

    public String getOuterHTML() {
        return outerHTML;
    }

    public InputChange setOuterHTML(String outerHTML) {
        this.outerHTML = outerHTML;
        return this;
    }

    public Element getInputElement() {
        return inputElement;
    }

    public void setInputElement(Element inputElement) {
        this.inputElement = inputElement;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getPlaceholderText() {
        return placeholderText;
    }

    public void setPlaceholderText(String placeholderText) {
        this.placeholderText = placeholderText;
    }

}
