package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.GiveUp;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

public class UnchartedAgent extends AbstractAgent{

    private boolean started = false;

    public static class Builder extends AbstractAgent.Builder<UnchartedAgent, Builder>{

        @Override
        protected UnchartedAgent create() {
            return new UnchartedAgent();
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    @Override
    public void observationHandler(TimelineEntity timelineEntity) {
        if(!started && timelineEntity instanceof Observation){
            started = true;
            emit(new GiveUp("UnchartedAgent is not implemented"));
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }
}
