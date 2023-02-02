package ca.ualberta.odobot.semanticflow.statemodel;

import org.jsoup.nodes.Element;

/**
 * A target container is a data structure containing a DOM element that is contextually important.
 * For example in a click event the target would be the element that was clicked.
 * In a DOM_ADD event the target would be the element that was added.
 * The target container for these examples would be {@link ca.ualberta.odobot.semanticflow.statemodel.impl.Interaction }
 * and {@link ca.ualberta.odobot.semanticflow.statemodel.impl.DomAdd} respectively.
 */
public interface ITargetContainer {

    String targetXpath();

    Element targetElement();
}
