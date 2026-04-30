package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import ca.ualberta.odobot.semanticflow.model.SelectEvent;
import io.vertx.core.json.JsonObject;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.*;
import java.util.stream.Collectors;

public class SelectOptionNode extends XpathAndBasePathNode {

    private Set<SelectEvent.Option> options = Set.of();

    public static SelectOptionNode fromRecord(Record record){

        Node n = record.get(0).asNode();

        SelectOptionNode result = fromRecord(record, new SelectOptionNode());
        result.options = new HashSet<>(n.get("options").asList(value -> {
            JsonObject json = new JsonObject(value.asString());
            return SelectEvent.Option.fromJson(json);
        }));

        return result;
    }

    public static List<SelectEvent.Option> optionsFromStrings(String [] optionStrings){
        return optionsFromStrings(Arrays.stream(optionStrings).toList());
    }

    public static List<SelectEvent.Option> optionsFromStrings(List<String> optionStrings){
        return optionStrings.stream().map(str -> SelectEvent.Option.fromJson(new JsonObject(str))).toList();
    }

    public List<String> getOptionsAsStrings(){
        List<String> result = new ArrayList<>();
        for(SelectEvent.Option option : options){
            result.add(option.toJson().encode());
        }
        return result;
    }

    public SelectOptionNode setOptions(Set<SelectEvent.Option> options) {
        this.options = options;
        return this;
    }

    public Set<SelectEvent.Option> getOptions() {
        return options;
    }

}
