package ca.ualberta.odobot.semanticflow.navmodel;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DynamicXPath {

    private static final Logger log = LoggerFactory.getLogger(DynamicXPath.class);

    public static DynamicXPath fromRow(Row row){

        DynamicXPath result = new DynamicXPath();

        if(row.getString("suffix") != null){
            result.setKnownSuffixes(new JsonArray(row.getString("suffix")).stream().map(o->(String)o).collect(Collectors.toSet()));
        }

        if(row.getString("suffix_pattern") != null){
            result.setSuffixPattern(Pattern.compile(row.getString("suffix_pattern")));
        }



        result.setPrefix(row.getString("prefix"));
        result.setDynamicTag(row.getString("tag"));


        return result;

    }
    public static DynamicXPath fromJson(JsonObject json){
        DynamicXPath result = new DynamicXPath();

        if(json.containsKey("prefix")){
            result.setPrefix(json.getString("prefix"));
        }else{
            return missingField("prefix", json);
        }

        if(json.containsKey("suffixPattern")){
            result.setSuffixPattern(Pattern.compile(json.getString("suffixPattern")));
        }

        if(json.containsKey("suffix")){
            result.setKnownSuffixes(new JsonArray(json.getString("suffix")).stream().map(o->(String)o).collect(Collectors.toSet()));
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

    private Pattern suffixPattern;

    private String dynamicTag;

    private Set<String> knownSuffixes = new HashSet<>();

    public Set<String> getKnownSuffixes() {
        return knownSuffixes;
    }

    public DynamicXPath setKnownSuffixes(Set<String> knownSuffixes) {
        this.knownSuffixes = knownSuffixes;
        if(this.knownSuffixes.size() == 1){
            this.suffix = knownSuffixes.iterator().next();
        }
        return this;
    }

    public DynamicXPath setKnownSuffixes(List<String> knownSuffixes) {
        Set<String> knownSuffixesSet = new HashSet<>(knownSuffixes);
        if(knownSuffixesSet.size() == 1){
            this.suffix = knownSuffixes.get(0);
        }
        this.knownSuffixes = knownSuffixesSet;
        return this;
    }

    public Pattern getSuffixPattern() {
        return suffixPattern;
    }

    public DynamicXPath setSuffixPattern(Pattern suffixPattern) {
        this.suffixPattern = suffixPattern;
        return this;
    }

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
        log.info("SampleXPath: {}", sampleXPath);
        log.info("Prefix: {}", prefix);
        log.info("SuffixPattern: {}", suffixPattern.pattern());
        log.info("Dynamic: {}", dynamicTag);

        log.info("startsWith: {}",sampleXPath.startsWith(prefix) );
        log.info("endsWith: {}", sampleXPath.endsWith(suffix));
        log.info("matchesSuffixPattern: {}", suffixPattern.asPredicate().test(sampleXPath));
        log.info("matchesDynamicTag: {}", matchesDynamicTag(sampleXPath));

        //NOTE: Check dynamic tag AFTER prefix and suffix, as dynamic tag extraction assumes prefix and suffix match.
        return sampleXPath.startsWith(prefix) && //Prefix matches
                (suffixPattern.asPredicate().test(sampleXPath) || (sampleXPath.equals(prefix + "/" + dynamicTag) )) && //Suffix matches
                matchesDynamicTag(sampleXPath); //Dynamic tag matches


    }

    public static Pattern toSuffixPattern(List<String> suffixes){

        //Get rid of duplicates
        Set<String> suffixSet = new HashSet<String>(suffixes);
        Iterator<String> it = suffixSet.iterator();
        StringBuilder sb = new StringBuilder();
        while (it.hasNext()){
            String _suffix = it.next();
            sb.append("(");
            sb.append(_suffix.replaceAll("/", "\\\\/"));
            sb.append(")");
            if(it.hasNext()){
                sb.append("|");
            }
        }

        log.info("SuffixPattern: {}", sb.toString());

        return Pattern.compile(sb.toString());

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
            log.info("matchesDynamicTag Logic");
            log.info("[1]{}", sampleXPath);

            var matcher = suffixPattern.matcher(sampleXPath);
            String sampleDynamicTagString = null;
            if(matcher.find()){
                var _suffix = matcher.group(0);
                sampleDynamicTagString = sampleXPath.substring(prefix.length(), sampleXPath.length()-_suffix.length() );

            }else{
                sampleDynamicTagString = sampleXPath.substring(sampleXPath.lastIndexOf("/")+1);
            }

            log.info("[2]{}", sampleDynamicTagString);
//            sampleDynamicTagString = sampleDynamicTagString.substring(sampleDynamicTagString.length()-suffix.length());
//            log.info("[3]{}", sampleDynamicTagString);
            String sampleTag = NavPath.extractTag(sampleDynamicTagString);
            log.info("extractedTag: {}", sampleTag);
            return this.dynamicTag.equals(sampleTag);


        }catch (StringIndexOutOfBoundsException e){
            log.error(e.getMessage(), e);

            return false;
        }
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("prefix", getPrefix())
                .put("dynamicTag", getDynamicTag());

        if (suffixPattern != null) {
            result.put("suffixPattern", getSuffixPattern().pattern());
        }

        if(suffix != null || knownSuffixes != null){
            result.put("suffix", knownSuffixes.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        }


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
        StringBuilder sb = new StringBuilder();
        sb.append(getPrefix());
        sb.append("/");
        sb.append(getDynamicTag());
        sb.append("/");

        if (suffixPattern != null){
            sb.append("{" + getSuffixPattern().pattern() + "}");
            return sb.toString();
        }

        if(suffix != null){
            sb.append(getSuffix());
        }



        return sb.toString();
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(21, 31);
        builder.append(prefix);
        builder.append(suffix);
        builder.append(dynamicTag);
        return builder.toHashCode();
    }
}
