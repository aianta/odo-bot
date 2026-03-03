package ca.ualberta.odobot.modelconstruction.eventlabeling;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LabelTrajectoryTask extends AbstractOpenAIStrategy implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(LabelTrajectoryTask.class);

    private Timeline trajectory;

    private List<EventDescription> eventDescriptions = new ArrayList<>();


    public LabelTrajectoryTask(JsonObject config, Timeline trajectory) {
        super(config);
        this.trajectory = trajectory;
    }


    @Override
    public void run() {

    }

    private String labelEvent(TimelineEntity entity, List<EventDescription> history) {
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("generateSemanticLabelForTrajectoryEvent").getString("systemPrompt")));




    }
}
