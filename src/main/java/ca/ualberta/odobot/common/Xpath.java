package ca.ualberta.odobot.common;

import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.Set;

public class Xpath {

    String value;

    public Xpath(String xpath){

       this.value = truncateXpath(xpath);

    }

    static Set<String> terminalElements = Set.of("/a", "/btn", "button", "svg");
    //static Set<String> exceptionElements = Set.of("input", "textarea", "option", "select");
    static Set<String> topPriorityElements = Set.of("/a");

    public static String truncateXpath(String xpath){

//        //First check to see that the xpath isn't an exception to our truncation rules.
//        //We decide this by checking to see if the terminal element appears in our exceptionElements
//        var terminalElement = xpath.substring(xpath.lastIndexOf("/"));
//        if(terminalElement.contains("[")){
//            terminalElement = terminalElement.substring(0, terminalElement.indexOf("["));
//        }
//        if(exceptionElements.contains(terminalElement)){
//            return xpath;
//        }

        for(String element: terminalElements){
            if (xpath.lastIndexOf(element) != -1){
                var trimmed = xpath.substring(0, xpath.lastIndexOf(element) + element.length());
                var remainder = xpath.substring(trimmed.length()).split("/")[0];

                var newXpath = trimmed + remainder;

                //If an anchor tag is in the xpath, trim to it.
                if(newXpath.lastIndexOf("/a") != -1){
                    trimmed = newXpath.substring(0, newXpath.lastIndexOf("/a") + "/a".length());
                    remainder = newXpath.substring(trimmed.length()).split("/")[0];
                }

                return trimmed + remainder;
            }
        }

        return xpath;
    }

    public String toString(){
        return value;
    }

    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder(51,93);
        builder.append(this.value);
        return builder.toHashCode();
    }

    /**
     * TODO: this method needs to be rewritten to account for the fact that 'div[1]' and 'div' are equivalent.
     * @param o   the reference object with which to compare.
     * @return
     */
    public boolean equals(Object o){
        if (o == this) return true;
        if (!(o instanceof Xpath)) return false;
        Xpath  other = (Xpath)o;
        return this.value.equals(other.value);
    }
}
