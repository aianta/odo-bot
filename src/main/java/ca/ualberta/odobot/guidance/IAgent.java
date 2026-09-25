package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IAgent {


    void observationHandler(TimelineEntity timelineEntity);


}
