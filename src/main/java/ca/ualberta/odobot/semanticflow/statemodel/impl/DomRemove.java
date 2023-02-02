package ca.ualberta.odobot.semanticflow.statemodel.impl;

import ca.ualberta.odobot.semanticflow.statemodel.ISemanticStructure;
import ca.ualberta.odobot.semanticflow.statemodel.ITargetContainer;
import org.jsoup.nodes.Element;

public class DomRemove implements ISemanticStructure, ITargetContainer {
    @Override
    public String targetXpath() {
        return null;
    }

    @Override
    public Element targetElement() {
        return null;
    }
}
