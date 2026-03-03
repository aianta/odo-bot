package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class GetDOMSnapshot extends Instruction{


    public String parameterName;
    public String parameterId;
    public String normalizedHref;


    @Override
    public boolean equals(Object o) {
        if(!(o instanceof GetDOMSnapshot)){
            return false;
        }

        GetDOMSnapshot other = (GetDOMSnapshot)o;

        return this.parameterName.equals(other.parameterName);
    }

    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder();
        builder.append(parameterName);
        return builder.toHashCode();
    }

    @Override
    public String toString() {
        return "Get DOMSnapshot to identify options for parameter (%s)".formatted( parameterName);
    }

    public JsonObject toJson(){
        JsonObject json = super.toJson();
        json.put("action", "getDOMSnapshot");
        json.put("parameterName", parameterName);
        json.put("parameterId", parameterId);
        return json;
    }
}
