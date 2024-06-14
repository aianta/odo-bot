package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.guidance.instructions.Instruction;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class XPathInstruction extends Instruction {

    public String xpath;

    public boolean equals(Object o){
        if(!(o instanceof XPathInstruction)){
            return false;
        }
        XPathInstruction other = (XPathInstruction) o;
        return xpath.equals(other.xpath);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(81, 53);
        builder.append(xpath);
        return builder.toHashCode();
    }

}
