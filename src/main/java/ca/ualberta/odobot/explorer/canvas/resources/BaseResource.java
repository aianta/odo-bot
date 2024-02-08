package ca.ualberta.odobot.explorer.canvas.resources;

public abstract class BaseResource {

    protected String identifier;
    protected String identifierRef;

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifierRef() {
        return identifierRef;
    }

    public void setIdentifierRef(String identifierRef) {
        this.identifierRef = identifierRef;
    }
}
