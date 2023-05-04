package ca.ualberta.odobot;


import ca.ualberta.odobot.semanticflow.model.InteractionType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A class for supplying data to tests.
 */
public class DatasetUtils {

    private static final Logger log = LoggerFactory.getLogger(DatasetUtils.class);

    private static final String TEST_DATASETS_DIR = "/testdata";

    //Holds all loaded records
    private static final List<JsonObject> masterSet = new ArrayList<>();

    public DatasetUtils () throws URISyntaxException {
        File [] datasets = new File(getClass().getResource(TEST_DATASETS_DIR).toURI()).listFiles();
        for(File f : datasets){
            masterSet.addAll((load(f)));
        }
    }

    public List<JsonObject> get(Predicate<JsonObject> filter){
        return masterSet.stream().filter(filter).toList();
    }

    private List<JsonObject> load(File f){
        try(
                FileInputStream fis = new FileInputStream(f)
        ) {
            JsonArray events = new JsonArray(IOUtils.toString(fis, "UTF-8"));
            return events.stream().map(o->(JsonObject)o).toList();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
        return null;
    }



    public static class DomEffectEventFilter implements Predicate<JsonObject>{

        @Override
        public boolean test(JsonObject event) {
            String eventType = event.getString("eventType");
            return eventType.equals("customEvent") && event.getString("eventDetails_name").equals("DOM_EFFECT");
        }
    }

    public static class InputEventFilter implements Predicate<JsonObject>{

        @Override
        public boolean test(JsonObject event) {
            String eventType = event.getString("eventType");
            return eventType.equals("interactionEvent") &&
                    InteractionType.getType(event.getString("eventDetails_name")) == InteractionType.INPUT;
        }
    }

    public static class ClickEventFilter implements Predicate<JsonObject>{

        @Override
        public boolean test(JsonObject event) {
            String eventType = event.getString("eventType");
            return eventType.equals("interactionEvent") &&
                    InteractionType.getType(event.getString("eventDetails_name")) == InteractionType.CLICK;
        }
    }
}
