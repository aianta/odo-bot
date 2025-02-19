package ca.ualberta.odobot;

import ca.ualberta.odobot.semanticflow.model.CheckboxEvent;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class JSoupParsingInputFieldTest {

    private static final Logger log = LoggerFactory.getLogger(JSoupParsingInputFieldTest.class);
    private static final String HTML = "<input type=\"checkbox\" value=\"1\" id=\"assignment_text_entry\" name=\"online_submission_types[online_text_entry]\" aria-label=\"Online Submission Type - Text Entry\" _odo_ishidden=\"false\">";


    @Test
    public void test(){

        Document document = Jsoup.parse(HTML);
        log.info(document.outerHtml());
        log.info(document.body().firstElementChild().outerHtml());

        assertEquals("checkbox",document.body().firstElementChild().attributes().get("type"));
    }

    @Test
    public void checkboxCasting(){
        InputChange inputChange = new InputChange();

        CheckboxEvent checkboxEvent = (CheckboxEvent) inputChange;

        log.info(checkboxEvent.getClass().getName());
    }

}
