package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.GiveUp;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

/**
 * Placeholder for {@link ExecutionMode#HYBRID}: gives up on its first observation.
 */
public class HybridAgent extends AbstractAgent {

    private boolean started = false;

    public static class Builder extends AbstractAgent.Builder<HybridAgent, Builder> {

        /**
         * The stub uses no navigation model.
         */
        @Override
        protected boolean validate() {
            return true;
        }

        @Override
        protected HybridAgent create() {
            return new HybridAgent();
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    @Override
    public void observationHandler(TimelineEntity timelineEntity) {
        if (!started && timelineEntity instanceof Observation) {
            started = true;
            emit(new GiveUp("Hybrid execution mode is not implemented"));
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }
}
