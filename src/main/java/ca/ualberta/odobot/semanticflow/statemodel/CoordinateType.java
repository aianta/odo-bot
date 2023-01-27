package ca.ualberta.odobot.semanticflow.statemodel;

public enum CoordinateType {
    DOM_EFFECT_ADD,
    DOM_EFFECT_REMOVE,
    DOM_EFFECT_SHOW,
    DOM_EFFECT_HIDE,
    CLICK,
    TRANSIENT /* For coordinates not associated with a particular event.
     For example the coordinates leading to the event coordinate. */
}
