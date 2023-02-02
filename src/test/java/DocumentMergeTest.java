import ca.ualberta.odobot.semanticflow.MergingAlgorithm;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;

public class DocumentMergeTest {
    private static final Logger log = LoggerFactory.getLogger(DocumentMergeTest.class);
    private static JsonObject eventJson;
    private static Document document;

    @BeforeAll
    static void setup() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/interactionEventExample.json");
        eventJson = new JsonObject(IOUtils.toString(fis, "UTF-8"));

        JsonObject domInfo = new JsonObject(eventJson.getString("eventDetails_domSnapshot"));
        String htmlData = domInfo.getString("outerHTML");
        document = Jsoup.parse(htmlData);
    }

    @Test
    void mergeCloneTest(){
        Document d1 = document.clone();
        Document d2 = document.clone();

        Document result = MergingAlgorithm.merge(d1, d2);
        log.info("{}", result.outerHtml());
    }



}
