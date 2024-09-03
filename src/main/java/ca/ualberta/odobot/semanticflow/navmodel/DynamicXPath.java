package ca.ualberta.odobot.semanticflow.navmodel;

import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicXPath {

    private static final Logger log = LoggerFactory.getLogger(DynamicXPath.class);

    public static DynamicXPath fromJson(JsonObject json){
        DynamicXPath result = new DynamicXPath();

        if(json.containsKey("prefix")){
            result.setPrefix(json.getString("prefix"));
        }else{
            return missingField("prefix", json);
        }


        if(json.containsKey("suffix")){
            result.setSuffix(json.getString("suffix"));
        }else {
            return missingField("suffix", json);
        }

        if(json.containsKey("dynamicTag")){
            result.setDynamicTag(json.getString("dynamicTag"));
        }else{
            return missingField("dynamicTag", json);
        }

        return result;
    }

    private static DynamicXPath missingField(String fieldName, JsonObject json){
        log.error("Could not create DynamicXPath from json object, missing '{}' field.\n{}", fieldName, json.encodePrettily());
        return null;
    }

    private String prefix;

    private String suffix;

    private String dynamicTag;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public String getDynamicTag() {
        return dynamicTag;
    }

    public void setDynamicTag(String dynamicTag) {
        this.dynamicTag = dynamicTag;
    }

    /**
     * Returns true if the provided sample XPath matches this dynamic XPath.
     *
     * @param sampleXPath the sample XPath to test.
     * @return
     */
    public boolean matches(String sampleXPath){



        //NOTE: Check dynamic tag AFTER prefix and suffix, as dynamic tag extraction assumes prefix and suffix match.
        return sampleXPath.startsWith(prefix) && //Prefix matches
                sampleXPath.endsWith(suffix) && //Suffix matches
                matchesDynamicTag(sampleXPath); //Dynamic tag matches


    }

    public boolean stillMatches(String sampleXPath){

        while (!matches(sampleXPath) && sampleXPath.contains("/")){
            sampleXPath = sampleXPath.substring(0, sampleXPath.lastIndexOf("/"));
        }

        if(sampleXPath.isEmpty() || sampleXPath.isBlank() || sampleXPath.length() == 0){
            return false;
        }else{
            return true;
        }

    }

    private boolean matchesDynamicTag(String sampleXPath){
        try{
            String sampleDynamicTagString = sampleXPath.substring(0, prefix.length());
            sampleDynamicTagString = sampleDynamicTagString.substring(sampleDynamicTagString.length()-suffix.length());
            String sampleTag = NavPath.extractTag(sampleDynamicTagString);

            return this.dynamicTag.equals(sampleTag);
        }catch (StringIndexOutOfBoundsException e){
            log.error(e.getMessage(), e);

            return false;
        }
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("prefix", getPrefix())
                .put("suffix", getSuffix())
                .put("dynamicTag", getDynamicTag());

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof DynamicXPath)){
            return false;
        }

        DynamicXPath other = (DynamicXPath) obj;
        return this.toJson().equals(other.toJson()); //This is probably slow...
    }

    public String toString(){
        return getPrefix() + "/" + getDynamicTag() + getSuffix();
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(21, 31);
        builder.append(prefix);
        builder.append(suffix);
        builder.append(dynamicTag);
        return builder.toHashCode();
    }
}
