package ca.ualberta.odobot.guidance.instructions;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class GetUIControlState extends XPathInstruction {

    public enum Type {
        TEXT,
        CHECKBOX,
        RADIO_BUTTON,
        SELECT,
        INPUT_COMBO_BOX
    }

    public Type type;



}
