package ca.ualberta.odobot.semanticflow.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public enum InteractionType{

    CLICK(Set.of("LINK_CLICK","TD_CLICK","BUTTON_CLICK_ACTUAL","BTN_CLICK", "LIST_ITEM_CLICK", "INPT_CLICK")),
    INPUT(Set.of("INPUT_CHANGE")),
    NETWORK_EVENT(Set.of("NETWORK_EVENT")),
    DOM_EFFECT(Set.of("DOM_EFFECT"));

    InteractionType(Set<String> logNames){
        this.logNames = logNames;
    }

    private static final Logger log = LoggerFactory.getLogger(InteractionType.class);

    public static InteractionType getType(String eventDetails_name){
        if(CLICK.logNames.contains(eventDetails_name))return CLICK;
        if(INPUT.logNames.contains(eventDetails_name))return INPUT;
        if(NETWORK_EVENT.logNames.contains(eventDetails_name))return NETWORK_EVENT;
        if(DOM_EFFECT.logNames.contains(eventDetails_name))return DOM_EFFECT;
        log.warn("Cannot find InteractionType for eventDetails_name: {}", eventDetails_name);
        return null;
    }

    Set<String> logNames;
}