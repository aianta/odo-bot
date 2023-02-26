package ca.ualberta.odobot.ranking;

import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.mappers.impl.ClickEventMapper;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.DistanceToTarget;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Tests DistanceToTargetRanking strategy
 * @author Alexandru Ianta
 */
public class DistanceToTargetRankingStrategyTest {
    private static final Logger log = LoggerFactory.getLogger(DistanceToTargetRankingStrategyTest.class);
    private static JsonObject sampleEvent;

    private static ClickEvent sampleClickEvent;

    @BeforeAll
    static void setup() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/interactionEventExample.json");
        sampleEvent = new JsonObject(IOUtils.toString(fis, "UTF-8"));
        sampleClickEvent = new ClickEventMapper().map(sampleEvent);
    }

    @Test
    void basic(){
        DistanceToTarget rankingStrategy = new DistanceToTarget();
        BasicStanfordNLPStrategy extractionStrategy = new BasicStanfordNLPStrategy();
        extractionStrategy.allowDuplicates(false);
        log.info("result: {}",rankingStrategy.getTerms(sampleClickEvent, extractionStrategy, SourceFunctions.TARGET_ELEMENT_TEXT.getFunction()));
    }
}
