package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class SelectEvent extends AbstractArtifact implements TimelineEntity{

    private static final Logger log = LoggerFactory.getLogger(SelectEvent.class);

    private Element selectElement;

    public SelectEvent setSelectElement(Element selectElement) {
        this.selectElement = selectElement;
        return this;
    }

    public Element selectElement() {
        return selectElement;
    }

    private Set<Option> options = new HashSet<>();

    public record Option(String xpath, boolean selected, String label, String value ) {

        public static Option fromJson(JsonObject jsonElement) {
            return new Option(jsonElement.getString("xpath"), jsonElement.getBoolean("selected"), jsonElement.getString("label"), jsonElement.getString("value"));
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("xpath", xpath)
                    .put("selected", selected)
                    .put("label", label)
                    .put("value", value);
        }
    }

    public Set<Option> options() {
        return options;
    }

    public SelectEvent addOption(JsonObject option){
        JsonObject elementJson = option.getJsonObject("element");
        Document fragment = Jsoup.parseBodyFragment(elementJson.getString("outerHTML"));
        Element optionElement = fragment.selectXpath("//option").first();

        options.add(new Option(option.getString("xpath"), elementJson.getBoolean("selected"), elementJson.getString("label"), optionElement.attribute("value").getValue()));
        return this;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public String symbol() {
        return "S";
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("symbol", symbol())
                .put("xpath", getXpath());
        return json;
    }

    @Override
    public long timestamp() {
        return getTimestamp().toInstant().toEpochMilli();
    }
}
