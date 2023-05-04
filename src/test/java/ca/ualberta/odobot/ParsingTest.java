package ca.ualberta.odobot;

import ca.ualberta.odobot.semanticflow.mappers.impl.ClickEventMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.DomEffectMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.InputChangeMapper;
import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.time.ZonedDateTime;

import static ca.ualberta.odobot.semanticflow.SemanticSequencer.TIMESTAMP_FIELD;
import static ca.ualberta.odobot.semanticflow.SemanticSequencer.timeFormatter;
import static org.junit.jupiter.api.Assertions.*;


public class ParsingTest {
    private static final Logger log = LoggerFactory.getLogger(ParsingTest.class);

    private static DatasetUtils data;
    private static ClickEventMapper clickEventMapper = new ClickEventMapper();
    private static InputChangeMapper inputChangeMapper = new InputChangeMapper();
    private static DomEffectMapper domEffectMapper = new DomEffectMapper();

    @BeforeAll
    static void setup() throws URISyntaxException {
        data = new DatasetUtils();
    }

    @Test
    void test() throws URISyntaxException {


    }

    @Test
    void testClickEvents(){
        data.get(new DatasetUtils.ClickEventFilter())
                .forEach(sample->{
                    log.info("{}", sample.encodePrettily());
                    ClickEvent clickEvent = clickEventMapper.map(sample);
                    clickEvent.setTimestamp(ZonedDateTime.parse(sample.getString(TIMESTAMP_FIELD), timeFormatter));

                    validateArtifact(clickEvent);
                    validateTimelineEntity(clickEvent);
                    validateClickEvent(clickEvent);
                });


    }

    private void validateTimelineEntity(TimelineEntity e){
        assertTrue(e.terms().size() > 0);
        assertTrue(e.size() > 0);
        assertNotNull(e.symbol());
        assertTrue(e.cssClassTerms().size() > 0);
        assertNotNull(e.timestamp());
    }

    private void validateClickEvent(ClickEvent event){
        assertNotNull(event.getTriggerElement());

    }

    private void validateArtifact(AbstractArtifact artifact){
        assertNotNull(artifact);
        assertNotNull(artifact.getBaseURI());
        assertNotNull(artifact.getDomSnapshot());
        assertNotNull(artifact.getHtmlId());
        assertNotNull(artifact.getId());
        assertNotNull(artifact.getTag());
        assertNotNull(artifact.getTargetElement());


        assertNotNull(artifact.getXpath());
    }

}
