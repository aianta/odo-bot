package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.mappers.impl.ClickEventMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.DomEffectMapper;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


public class SemanticSequencer {
    private static final Logger log = LoggerFactory.getLogger(SemanticSequencer.class);

    private Effect testEffect = new Effect();

    private DomEffectMapper domEffectMapper = new DomEffectMapper();
    private ClickEventMapper clickEventMapper = new ClickEventMapper();

    public void parse(List<JsonObject> events){
        events.forEach(event->parse(event));
        log.info("Effect parsed: {}", testEffect.toString());
    }

    private void parse(JsonObject event){


        switch (event.getString("eventType")){
            case "interactionEvent":
                switch (ClickEvent.InteractionType.getType(event.getString("eventDetails_name"))){
                    case CLICK -> {
                        ClickEvent clickEvent = clickEventMapper.map(event);

                    }
                    case INPUT -> {}
                }
                break;
            case "customEvent":
                if(event.getString("eventDetails_name").equals("DOM_EFFECT")){
                    DomEffect domEffect = domEffectMapper.map(event);
                    testEffect.add(domEffect);
                }
        }
    }
}
