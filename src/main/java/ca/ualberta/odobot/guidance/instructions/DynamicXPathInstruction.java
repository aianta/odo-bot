package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;

public class DynamicXPathInstruction extends Instruction {

    public DynamicXPath dynamicXPath;



    public boolean equals(Object o){
        if(!(o instanceof DynamicXPathInstruction)){
            return false;
        }
        return dynamicXPath.equals(((DynamicXPathInstruction) o).dynamicXPath);
    }

    public int hashCode(){
        return dynamicXPath.hashCode();
    }

    public String toString(){
        if(dynamicXPath != null){
            return "Instruction with dynamicXpath: " + dynamicXPath.toString();
        }else{
            return "Instruction with multiple dynamicXPaths!";
        }



    }
}
