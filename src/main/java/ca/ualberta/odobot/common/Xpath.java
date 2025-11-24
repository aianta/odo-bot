package ca.ualberta.odobot.common;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class Xpath {

    String value;

    public Xpath(String xpath){

        if (xpath.lastIndexOf("/a") != -1){
            this.value = xpath.substring(0, xpath.lastIndexOf("/a") + 2);
        } else if (xpath.lastIndexOf("/btn") != -1){
            this.value = xpath.substring(0, xpath.lastIndexOf("/btn") + 4);
        } else if (xpath.lastIndexOf("button") != -1){
            this.value = xpath.substring(0, xpath.lastIndexOf("button") + 6);
        } else if (xpath.lastIndexOf("svg") != -1){
            this.value = xpath.substring(0, xpath.lastIndexOf("svg") + 3);
        }else{
            this.value = xpath;
        }

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
