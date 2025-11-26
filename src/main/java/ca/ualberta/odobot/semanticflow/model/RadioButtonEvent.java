package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.common.BasePathAndXpath;
import ca.ualberta.odobot.common.Xpath;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class RadioButtonEvent extends InputChange implements TimelineEntity{

    private static final Logger log = LoggerFactory.getLogger(RadioButtonEvent.class);

    private String radioGroup;

    private ArrayList<RadioButton> options = new ArrayList<RadioButton>();

    public static class RadioButton {
        private String basePath;
        private String xpath;
        private String html;
        private boolean checked;
        private String value;

        public static RadioButton fromJson(JsonObject json){
            RadioButton result = new RadioButton(
                    json.getString("basePath"),
                    json.getString("xpath"),
                    json.getString("html"),
                    json.getBoolean("checked"),
                    json.getString("value")
            );
            return result;
        }

        public RadioButton(String xpath, String html, boolean checked, String value) {
            this.xpath = xpath;
            this.html = html;
            this.checked = checked;
            this.value = value;
        }

        public RadioButton(String basePath, String xpath, String html, boolean checked, String value) {
            this.basePath = basePath;
            this.xpath = xpath;
            this.html = html;
            this.checked = checked;
            this.value = value;
        }

        public BasePathAndXpath getBasePathAndXpath(){
            return new BasePathAndXpath(getBasePath(), new Xpath(getXpath()));
        }

        public String getBasePath() {
            return basePath;
        }

        public RadioButton setBasePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public String getXpath() {
            return xpath;
        }

        public RadioButton setXpath(String xpath) {
            this.xpath = xpath;
            return this;
        }

        public String getHtml() {
            return html;
        }

        public RadioButton setHtml(String html) {
            this.html = html;
            return this;
        }

        public boolean isChecked() {
            return checked;
        }

        public RadioButton setChecked(boolean checked) {
            this.checked = checked;
            return this;
        }

        public String getValue() {
            return value;
        }

        public RadioButton setValue(String value) {
            this.value = value;
            return this;
        }

        public JsonObject toJson() {
            JsonObject result = new JsonObject()
                    .put("basePath", basePath)
                    .put("xpath", xpath)
                    .put("html", html)
                    .put("checked", checked)
                    .put("value", value);
            return result;
        }
    }

    public RadioButtonEvent setRadioGroup(String radioGroup) {
        this.radioGroup = radioGroup;
        return this;
    }

    public String getRadioGroup() {return radioGroup;}

    public RadioButton getCheckedButton(){
        for(RadioButton rb:options){
            if(rb.isChecked()){
                return rb;
            }
        }
        log.info("No radio button in this group ({}) is checked!", getRadioGroup());
        return null;
    }

    public void setBaseURI(String baseURI){
        super.setBaseURI(baseURI);
        this.options.forEach(option->option.setBasePath(getBasePath()));
    }

    public ArrayList<RadioButton> getOptions() {
        return options;
    }

    public RadioButtonEvent addOption(RadioButton radioButton) {
        options.add(radioButton);
        return this;
    }

    public int size(){return 1;}

    public String symbol () {return "RAD";}

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("basePath", getBaseURI())
                .put("xpath", getXpath())
                .put("radioGroup", getRadioGroup())
                .put("options", getOptions().stream().map(RadioButton::toJson)
                        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

        return result;
    }

    public long timestamp(){
        return getTimestamp().toInstant().toEpochMilli();
    }

}
