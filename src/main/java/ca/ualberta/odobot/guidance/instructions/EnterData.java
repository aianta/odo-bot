package ca.ualberta.odobot.guidance.instructions;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class EnterData extends XPathInstruction{

    public String data;
    public String parameterId;

    public boolean equals(Object o){
        if(!(o instanceof EnterData)){
            return false;
        }
        EnterData other = (EnterData) o;
        return xpath.equals(other.xpath) && data.equals(other.data) && parameterId.equals(other.parameterId);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(81, 53);
        builder.append(xpath);
        builder.append(data);
        builder.append(parameterId);
        return builder.toHashCode();
    }
}
