package ca.ualberta.odobot.semanticflow.statemodel;

import java.util.Collection;

/**
 * A trace is an ordered sequence of semantic structures representing user interaction with the application, and the
 * application's 'responses' to those interactions.
 */
public interface ITrace {

    /**
     * @return the number of structures in this trace.
     */
    int length();

    /**
     * @param index index of the semantic structure to return
     * @return the {@link ca.ualberta.odobot.semanticflow.statemodel.ISemanticStructure} at index in the trace.
     */
    ISemanticStructure get(int index);

    /**
     * Returns all semantic structures of a particular class contained in the trace.
     * @param tClass the class of the semantic structures to return
     * @param <T>
     * @return semantic structures of tClass in this trace.
     */
    <T extends ISemanticStructure> Collection<T> getAll(Class<T> tClass);

}
