package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class SelectOption extends XPathInstruction{

    public String value;
    public String parameterId;

    public boolean equals(Object o){
        if(!(o instanceof SelectOption)){
            return false;
        }
        SelectOption other = (SelectOption) o;
        return xpath.equals(other.xpath) && value.equals(other.value) && parameterId.equals(other.parameterId);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(83, 59);
        builder.append(xpath);
        builder.append(value);
        return builder.toHashCode();
    }

    public JsonObject toJson(){
        return super.toJson()
                .put("action", "selectOption")
                .put("value", value)
                .put("xpath", xpath)
                .put("parameterId", parameterId);
    }

    public String toString(){
        return "SelectOption on Xpath " + xpath + " with value: " + value + " and parameterId: " + parameterId;
    }

}
