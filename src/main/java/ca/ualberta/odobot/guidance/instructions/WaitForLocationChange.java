package ca.ualberta.odobot.guidance.instructions;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class WaitForLocationChange extends Instruction{

    public String path;

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof WaitForLocationChange)){
            return false;
        }
        WaitForLocationChange other = (WaitForLocationChange)obj;
        return this.path.equals(other.path);
    }

    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder(843, 71);
        builder.append(this.path);
        return builder.toHashCode();
    }
}
