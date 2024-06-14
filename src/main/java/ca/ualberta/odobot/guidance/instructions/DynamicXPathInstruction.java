package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;

public class DynamicXPathInstruction extends Instruction {

    public DynamicXPath dynamicXPath;

    public boolean equals(Object o){
        return dynamicXPath.equals(o);
    }

    public int hashCode(){
        return dynamicXPath.hashCode();
    }
}
