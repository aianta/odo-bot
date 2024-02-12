package ca.ualberta.odobot.explorer.canvas.resources;

import java.util.Random;

public abstract class BaseResource {

    protected static Random random = new Random();
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

    public String makeEdit(String body){
        //Randomly pick some sub-section of the body
        String result = body.substring(random.nextInt(0, body.length()));
        if(result.length() < body.length()/2){ // If the randomly selected subsection is greater than half the original content.
            result += " " +  body; //Add the subsection and body together.
        }
        return result;
    }
}
