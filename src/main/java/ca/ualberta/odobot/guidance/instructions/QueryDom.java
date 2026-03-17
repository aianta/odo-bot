package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class QueryDom extends DynamicXPathInstruction{

    public String parameterId;

    public boolean equals(Object o){
        if(!(o instanceof QueryDom)){
            return false;
        }

        QueryDom other = (QueryDom) o;

        if(parameterId == null){
            return other.parameterId == null && dynamicXPath.equals(other.dynamicXPath);
        }

        return dynamicXPath.equals(other.dynamicXPath) && parameterId.equals(other.parameterId);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(81, 53);
        builder.append(dynamicXPath.hashCode());
        if(parameterId != null){
            builder.append(parameterId);
        }

        return builder.toHashCode();
    }

    public JsonObject toJson(){
        JsonObject result =
            super.toJson()
            .put("action", "queryDom");

        if(this.parameterId != null){
            result.put("parameterId", this.parameterId);
        }

        //OdoX expects the dynamic xpath to be in the 'xpath' field.
        result.put("xpath", this.dynamicXPath.toJson());

        return result;
    }
}
