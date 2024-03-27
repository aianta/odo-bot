package ca.ualberta.odobot.domsequencing;

import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.Set;

public record DOMSegment (String tag, String className, String xpath) {

    public JsonObject toJson(){
        var result = new JsonObject()
                .put("tag", tag)
                .put("class", className)
                .put("xpath", xpath);
        return result;
    }

    /**
     * Relevant documentation:
     * https://www.baeldung.com/java-hashcode
     * https://commons.apache.org/proper/commons-lang/apidocs/org/apache/commons/lang3/builder/HashCodeBuilder.html
     *
     * @return
     */
    public int hashCode(){
        return new HashCodeBuilder(19,31)
                .append(tag)
                .append(className)
                .toHashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof DOMSegment)){
            return false;
        }

        DOMSegment other = (DOMSegment) obj;

        return this.tag.equals(other.tag) && this.className.equals(other.className);
    }
}
