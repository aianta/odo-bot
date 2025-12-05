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

        return dynamicXPath.equals(other.dynamicXPath) && parameterId.equals(other.parameterId);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(81, 53);
        builder.append(dynamicXPath.hashCode());
        builder.append(parameterId);
        return builder.toHashCode();
    }

    public JsonObject toJson(){
        return super.toJson()
                .put("action", "queryDom")
                .put("parameterId", this.parameterId)
                //OdoX expects the dynamic xpath to be in the 'xpath' field.
                .put("xpath", this.dynamicXPath.toJson());
    }
}
