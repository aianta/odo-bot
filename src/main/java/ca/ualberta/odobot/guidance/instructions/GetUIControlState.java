package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class GetUIControlState extends XPathInstruction {

    public enum Type {
        TEXT,
        CHECKBOX,
        RADIO_BUTTON,
        SELECT,
        INPUT_COMBO_BOX,
        TINY_MCE_EDITOR
    }

    public Type type;
    public String editorId;
    public String parameterId;

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof GetUIControlState)){
            return false;
        }

        GetUIControlState other = (GetUIControlState) o;

        if (this.editorId != null) {
            return other.editorId.equals(this.editorId);
        }

        return this.type == other.type && this.xpath.equals(other.xpath);
    }

    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder(71, 53);
        if (this.editorId != null) {
            builder.append(this.editorId);
        }else{
            builder.append(this.xpath);
            builder.append(this.type.name());
        }
        return builder.toHashCode();
    }

    public JsonObject toJson(){
        JsonObject json = super.toJson();
        json.put("action", "getUIControlState");
        json.put("xpath", this.xpath);
        json.put("uiControlType", this.type.name());

        if(parameterId != null){
            json.put("parameterId", parameterId);
        }

        if(editorId != null){
            json.put("editorId", editorId);
        }
        return json;
    }


}
