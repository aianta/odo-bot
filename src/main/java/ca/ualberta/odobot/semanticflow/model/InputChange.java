package ca.ualberta.odobot.semanticflow.model;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InputChange extends AbstractArtifact{

    private static final Logger log = LoggerFactory.getLogger(InputChange.class);
    private Element inputElement;
    private String value;
    private String placeholderText;

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
