package ca.ualberta.odobot.guidance.instructions;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class WaitForNetworkEvent extends Instruction{

    public String method;
    public String path;

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof WaitForNetworkEvent)){
            return false;
        }

        WaitForNetworkEvent other = (WaitForNetworkEvent) obj;
        return this.method.equals(other.method) && this.path.equals(other.path);
    }

    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder(643, 47);
        builder.append(this.method);
        builder.append(this.path);
        return builder.toHashCode();
    }
}
