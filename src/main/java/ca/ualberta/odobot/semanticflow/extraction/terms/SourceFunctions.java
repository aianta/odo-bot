package ca.ualberta.odobot.semanticflow.extraction.terms;

import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;

import java.util.function.Function;

public enum SourceFunctions {

    TARGET_ELEMENT_TAG(
            (artifact)->artifact.getTargetElement().tagName()
    ),
    TARGET_ELEMENT_TEXT(
            (artifact)->artifact.getTargetElement().text()
    ),
    TARGET_ELEMENT_CSS_CLASSES(
            (artifact)->artifact.getTargetElement().className()
    ),
    TARGET_ELEMENT_ID(
            (artifact)->artifact.getTargetElement().id()
    );


    Function<AbstractArtifact, String> function;

    SourceFunctions(Function<AbstractArtifact, String> function){
        this.function = function;
    }

    public Function<AbstractArtifact, String> getFunction(){
        return function;
    }

}
