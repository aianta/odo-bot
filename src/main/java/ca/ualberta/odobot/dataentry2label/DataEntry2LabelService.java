package ca.ualberta.odobot.dataentry2label;

import ca.ualberta.odobot.dataentry2label.impl.DataEntry2LabelServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface DataEntry2LabelService {

    static DataEntry2LabelService create(Vertx vertx, JsonObject config, Strategy strategy){
        return new DataEntry2LabelServiceImpl(vertx, config, strategy);
    }

    static DataEntry2LabelService createProxy(Vertx vertx, String address){
        return new DataEntry2LabelServiceVertxEBProxy(vertx, address);
    }

    /**
     * Give a list of DataEntry labels and descriptions, attempts to remove duplicates by 'clustering' together conceptually similar labels.
     * @param labels
     * @return A JsonArray of objects containing standardized names and descriptions for similar data entry annotations. See dataentry2label.yaml for exact format.
     */
    Future<JsonArray> standardizeLabels(List<JsonObject> labels);

    /**
     *
     * @param dataEntryInfo A json object expected to contain: 'xpath', 'htmlContext', 'inputElement', and 'enteredData'
     * @return A json object containing: 'label', 'description', and 'xpath'.
     */
    Future<JsonObject> generateLabelAndDescription(JsonObject dataEntryInfo);


}
