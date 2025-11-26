package ca.ualberta.odobot.common;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class BasePathAndXpath {

    private String basePath;
    private Xpath xpath;

    public BasePathAndXpath(String baseUri, Xpath xpath){
        setBasePath(baseUri);
        this.xpath = xpath;
    }

    public static BasePathAndXpath fromString(String input){
        String[] split = input.split(",");
        return new BasePathAndXpath(split[0], new Xpath(split[1]));
    }

    public String toString(){
        return this.basePath + "," +  this.xpath.toString();
    }

    public String getBasePath() {
        return basePath;
    }

    public BasePathAndXpath setBasePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    public Xpath getXpath() {
        return xpath;
    }

    public BasePathAndXpath setXpath(Xpath xpath) {
        this.xpath = xpath;
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof BasePathAndXpath)){
           return false;
        }

        BasePathAndXpath other = (BasePathAndXpath)obj;
        return this.xpath.equals(other.xpath) && this.basePath.equals(other.basePath);
    }

    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder(31,91);
        builder.append(this.basePath);
        builder.append(this.xpath.toString());
        return builder.toHashCode();
    }
}
