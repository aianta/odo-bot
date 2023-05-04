package ca.ualberta.odobot;

import ca.ualberta.odobot.xes.XesTransformer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.deckfour.xes.model.XLog;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;


public class XesTransformerTest {

    private static final Logger log = LoggerFactory.getLogger(XesTransformer.class);
    private static final String TIMELINE_JSONS = "/xesTransformerInput_timelines.json";
    private static final String ACTIVITY_LABEL_JSON = "/xesTransformerInput_entityMappings.json";

    private final JsonArray timelines;
    private final JsonObject activityLabels;

    public XesTransformerTest() throws URISyntaxException, IOException {
        log.info("Loading timeline jsons from: {}", TIMELINE_JSONS);
        File timelineInputFile = new File(getClass().getResource(TIMELINE_JSONS).toURI());
        File activityLabelsFile = new File(getClass().getResource(ACTIVITY_LABEL_JSON).toURI());

        try(
                FileInputStream fisTimelines = new FileInputStream(timelineInputFile);
                FileInputStream fisActivityLabels = new FileInputStream(activityLabelsFile)
                ){
            timelines = new JsonArray(IOUtils.toString(fisTimelines, "UTF-8"));
            activityLabels = new JsonObject(IOUtils.toString(fisActivityLabels, "UTF-8"));
        }

    }


    @Test
    void createXESLog(){
        XesTransformer transformer = new XesTransformer();

        XLog xlog = transformer.parse(timelines, activityLabels);
        File out = new File("log.xml");

        transformer.save(xlog, out);

    }

}
