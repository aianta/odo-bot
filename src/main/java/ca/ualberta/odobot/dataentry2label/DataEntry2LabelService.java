package ca.ualberta.odobot.dataentry2label;

import ca.ualberta.odobot.dataentry2label.impl.DataEntry2LabelServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@ProxyGen
public interface DataEntry2LabelService {

    static DataEntry2LabelService create(Vertx vertx, JsonObject config, Strategy strategy){
        return new DataEntry2LabelServiceImpl(vertx, config, strategy);
    }

    static DataEntry2LabelService createProxy(Vertx vertx, String address){
        return new DataEntry2LabelServiceVertxEBProxy(vertx, address);
    }


    /**
     *
     * @param dataEntryInfo A json object expected to contain: 'xpath', 'htmlContext', 'inputElement', and 'enteredData'
     * @return A json object containing: 'label', 'description', and 'xpath'.
     */
    Future<JsonObject> generateLabelAndDescription(JsonObject dataEntryInfo);


}
