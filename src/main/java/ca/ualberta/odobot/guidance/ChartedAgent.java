package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;

import java.util.List;

public class ChartedAgent extends AbstractAgent{

    private List<NavPath> navPaths;

    public static class Builder extends AbstractAgent.Builder{

        ChartedAgent build(){


            ChartedAgent chartedAgent = new ChartedAgent();
            chartedAgent.graphDB =  graphDB;
            chartedAgent.sqlite = sqlite;
            chartedAgent.neo4J = neo4J;
            chartedAgent.task = task;

            return chartedAgent;
        }
    }

    @Override
    public void observationHandler(TimelineEntity timelineEntity) {



    }
}
