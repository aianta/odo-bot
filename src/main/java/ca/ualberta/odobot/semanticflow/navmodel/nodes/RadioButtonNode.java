package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import ca.ualberta.odobot.common.BasePathAndXpath;
import ca.ualberta.odobot.semanticflow.model.RadioButtonEvent;
import io.vertx.core.json.JsonObject;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.ArrayList;
import java.util.List;

public class RadioButtonNode extends XpathAndBasePathNode {


    private String radioGroup;

    private List<RadioButtonEvent.RadioButton> radioButtons = new ArrayList<>();

    public static RadioButtonNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        RadioButtonNode result = fromRecord(record, new RadioButtonNode());
        result.setRadioGroup(n.get("radioGroup").asString());

        result.setRadioButtons(n.get("relatedElements").asList(value -> {
            JsonObject jsonButton = new JsonObject(value.asString());

            RadioButtonEvent.RadioButton radioButton = RadioButtonEvent.RadioButton.fromJson(jsonButton);
            return radioButton;
        }));

        return result;

    }

    public List<String> getXpaths(){
        return radioButtons.stream().map(RadioButtonEvent.RadioButton::getBasePathAndXpath).map(BasePathAndXpath::toString).toList();
    }


    public String getRadioGroup() {
        return radioGroup;
    }

    public RadioButtonNode setRadioGroup(String radioGroup) {
        this.radioGroup = radioGroup;
        return this;
    }

    public List<RadioButtonEvent.RadioButton> getRadioButtons() {
        return radioButtons;
    }

    public RadioButtonNode setRadioButtons(List<RadioButtonEvent.RadioButton> radioButtons) {
        this.radioButtons = radioButtons;
        return this;
    }

    public List<String> getButtonsAsStrings(){
        List<String> result = new ArrayList<>();

        for(RadioButtonEvent.RadioButton button: radioButtons){
            result.add(button.toJson().encode());
        }

        return result;

    }
}
