package ca.ualberta.odobot.semanticflow.navmodel;

public class DynamicXPath {

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

    private boolean matchesDynamicTag(String sampleXPath){

        String sampleDynamicTagString = sampleXPath.substring(0, prefix.length());
        sampleDynamicTagString = sampleDynamicTagString.substring(sampleDynamicTagString.length()-suffix.length());
        String sampleTag = NavPath.extractTag(sampleDynamicTagString);

        return this.dynamicTag.equals(sampleTag);
    }
}
