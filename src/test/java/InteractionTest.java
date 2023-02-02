import ca.ualberta.odobot.semanticflow.statemodel.impl.Interaction;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;


public class InteractionTest {
    private static final Logger log = LoggerFactory.getLogger(InteractionTest.class);
    private static JsonObject eventJson;

    @BeforeAll
    static void setup() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/interactionEventExample.json");
        eventJson = new JsonObject(IOUtils.toString(fis,"UTF-8"));
    }


    @Test
    void pruneTest(){
        Interaction interaction = new Interaction(eventJson);

        log.info("pruned document: \n{}",interaction.getDocument().toString());
    }






}