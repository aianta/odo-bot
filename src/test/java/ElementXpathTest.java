import ca.ualberta.odobot.semanticflow.Utils;
import ca.ualberta.odobot.semanticflow.statemodel.impl.Interaction;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ElementXpathTest {

    private static final Logger log = LoggerFactory.getLogger(ElementXpathTest.class);
    private static JsonObject eventJson;
    private static Document document;
    private static String jsonXpath;

    @BeforeAll
    static void setup() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/interactionEventExample.json");
        eventJson = new JsonObject(IOUtils.toString(fis, "UTF-8"));

        JsonObject domInfo = new JsonObject(eventJson.getString("eventDetails_domSnapshot"));
        String htmlData = domInfo.getString("outerHTML");
        document = Jsoup.parse(htmlData);
        jsonXpath = eventJson.getString("eventDetails_xpath");
    }

    @Test
    void simpleXpath(){

        Element targetElement = document.selectXpath(jsonXpath).first();
        String computedXpath = Utils.computeXpath(targetElement);

        Interaction interaction = new Interaction(eventJson);
        log.info("pruned document: \n{}",interaction.getDocument().toString());

        assertEquals(jsonXpath, computedXpath);

    }


}
