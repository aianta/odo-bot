package ca.ualberta.odobot.semanticflow.statemodel;

/**
 * A tracer creates and updates {@link ca.ualberta.odobot.semanticflow.statemodel.ITrace}'s.
 */
public interface ITracer {

    //TODO - make this static?
    ITrace createTrace();

    void updateTrace(IEvent event);
}
