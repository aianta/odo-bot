package ca.ualberta.odobot.modelconstruction.eventlabeling;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Consumer;

public class LabelTrajectoryTask extends AbstractOpenAIStrategy implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(LabelTrajectoryTask.class);

    private Timeline trajectory;

    private List<EventDescription> eventDescriptions = new ArrayList<>();

    private Promise<String> resultPromise = Promise.promise();

    private String syntheticTaskDescription;
    private Instant taskCreationTime = null;

    private Consumer<EventDescription> eventDescriptionConsumer;

    public LabelTrajectoryTask setEventDescriptionConsumer(Consumer<EventDescription> eventDescriptionConsumer) {
        this.eventDescriptionConsumer = eventDescriptionConsumer;
        return this;
    }

    public Timeline getTrajectory() {
        return trajectory;
    }

    public Instant getTaskCreationTime() {
        return taskCreationTime;
    }

    public List<EventDescription> getEventDescriptions() {
        return eventDescriptions;
    }

    public String getSyntheticTaskDescription() {
        return syntheticTaskDescription;
    }

    public LabelTrajectoryTask(JsonObject config, Timeline trajectory) {
        super(config);
        this.trajectory = trajectory;

    }

    public Promise<String> getPromise() {
        return resultPromise;
    }


    @Override
    public void run() {

        ListIterator<TimelineEntity> it = trajectory.listIterator();
        while (it.hasNext()) {
            TimelineEntity entity = it.next();
            log.info("Trajectory {} - Event {}: {}", trajectory.getId(), it.previousIndex()+1, entity.symbol());
            String description = labelEvent(entity, eventDescriptions);
            EventDescription eventDescription = new EventDescription(description, entity, config.getString("model"), it.previousIndex());
            eventDescriptions.add(eventDescription);
            if(eventDescriptionConsumer != null){
                eventDescriptionConsumer.accept(eventDescription);
            }
        }


        log.info("Descriptions for trajectory events:");

        log.info("{}", writeEventsAsStory(eventDescriptions));

        syntheticTaskDescription = labelTrajectory(eventDescriptions);
        taskCreationTime = Instant.now();
        log.info("Synthetic task description for trajectory {}:\n{}", trajectory.getId(), syntheticTaskDescription );



        resultPromise.complete(syntheticTaskDescription);


    }

    private String writeEventsAsStory(List<EventDescription> eventDescriptions){
        ListIterator<EventDescription> listIterator = eventDescriptions.listIterator();
        StringBuilder story = new StringBuilder();
        while (listIterator.hasNext()){
            EventDescription ed = listIterator.next();

            if (listIterator.previousIndex() > 0){
                story.append("Then, ");
            }
            story.append(ed.getDescription()).append("\n");
        }
        return story.toString();
    }

    private String labelTrajectory(List<EventDescription> interactions){
        log.info("Generating synthetic task description for trajectory {}", trajectory.getId());
        List<ChatRequestMessage> chatRequestMessages = new ArrayList<>();

        String systemPrompt = config.getJsonObject("generateSyntheticTaskForTrajectory").getString("systemPrompt");
        chatRequestMessages.add(new ChatRequestSystemMessage(systemPrompt));

        String userPromptTemplate = """
        Observed interactions:
        %s
        
        Task Description:
        """.formatted(writeEventsAsStory(interactions));

        chatRequestMessages.add(new ChatRequestUserMessage(userPromptTemplate));

        return executeChatCompletion(chatRequestMessages);
    }

    private String labelEvent(TimelineEntity entity, List<EventDescription> history) {
        log.info("Labeling event {} from trajectory {}, history size: {}", entity.symbol(), trajectory.getId(), history.size());
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String systemPrompt = config.getJsonObject("generateSemanticLabelForTrajectoryEvent").getString("systemPrompt");
        chatMessages.add(new ChatRequestSystemMessage(systemPrompt));

        String encodedEvent = EventEncoder.encode(entity);
        String historyString = buildHistoryString(history);

        String userPromptTemplate = """
      
      History leading up to the current event:
      %s
      
      Current Event:
      %s
      
      Your description of the current event:
      """.formatted(historyString, encodedEvent);

        if(history.isEmpty()){
            userPromptTemplate = """
      
      There is no history leading up to the current event, it is the first event.
      
      Current Event:
      %s
      
      Your description of the current event:
      """.formatted(encodedEvent);
        }




        chatMessages.add(new ChatRequestUserMessage(userPromptTemplate));

        log.info("{}", systemPrompt + userPromptTemplate);

        return executeChatCompletion(chatMessages);
    }



    private String buildHistoryString(List<EventDescription> history) {
        StringBuilder sb = new StringBuilder();

        ListIterator<EventDescription> it = history.listIterator();
        while (it.hasNext()) {
            EventDescription ed = it.next();
            sb.append("Event %d Description: %s\n".formatted(it.previousIndex()+1, ed.getDescription()));
            //sb.append("Event Description:\n%s\n".formatted(ed.getDescription()));
            //sb.append("Event Details:\n%s\n".formatted(EventEncoder.encode(ed.getEntity())));
            sb.append("\n");
        }
        return sb.toString();
    }
}
