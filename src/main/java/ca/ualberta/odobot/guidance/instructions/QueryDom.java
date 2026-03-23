package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class QueryDom extends DynamicXPathInstruction{

    public String parameterId;
    public Set<DynamicXPath> dynamicXPaths = new HashSet<DynamicXPath>();

    public boolean equals(Object o){
        if(!(o instanceof QueryDom)){
            return false;
        }

        QueryDom other = (QueryDom) o;

        if(parameterId == null){
            return other.parameterId == null && dynamicXPath.equals(other.dynamicXPath);
        }

        if(dynamicXPaths != null && !dynamicXPaths.isEmpty()){

            return dynamicXPaths.size() == other.dynamicXPaths.size() &&
                    other.dynamicXPaths.containsAll(dynamicXPaths);

        }

        return dynamicXPath.equals(other.dynamicXPath) && parameterId.equals(other.parameterId);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(81, 53);

        if(dynamicXPath != null){
            builder.append(dynamicXPath.hashCode());
        }

        if(parameterId != null){
            builder.append(parameterId);
        }


        if(dynamicXPaths != null && !dynamicXPaths.isEmpty()){
            for(DynamicXPath dXpath : dynamicXPaths){
                builder.append(dXpath.hashCode());
            }
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
        if(this.dynamicXPaths != null && !dynamicXPaths.isEmpty()){
            result.put("xpath", dynamicXPaths.stream()
                    .map(DynamicXPath::toJson)
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        }else{
            result.put("xpath", this.dynamicXPath.toJson());
        }


        return result;
    }

    public String toString(){
        if(this.dynamicXPaths != null && !this.dynamicXPaths.isEmpty()){
            StringBuilder sb = new StringBuilder();
            sb.append("Query Dom instruction with %s dynamicXpath(s) [".formatted(this.dynamicXPaths.size()));
            Iterator<DynamicXPath> it = this.dynamicXPaths.iterator();
            while (it.hasNext()){
                DynamicXPath dx = it.next();
                sb.append(dx.toJson().encodePrettily());
                if(it.hasNext()){
                    sb.append(",\n");
                }
            }
            sb.append("]");
            return sb.toString();
        }else{
            return super.toString();
        }

    }
}
