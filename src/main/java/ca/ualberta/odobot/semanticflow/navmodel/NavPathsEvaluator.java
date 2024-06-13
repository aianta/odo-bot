package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class NavPathsEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(NavPathsEvaluator.class);

    public List<Path> _paths = new ArrayList<>();

    Node targetNode = null;

    public NavPathsEvaluator(Node targetNode){
        this.targetNode = targetNode;
    }

    @Override
    public Evaluation evaluate(Path path) {
        log.info("hit evaluate!");

        String endId = (String)targetNode.getProperty("id");
        String currentId = (String)path.endNode().getProperty("id");

        log.info("CurrentId: {}, endId: {}", currentId, endId);

        if(currentId.equals(endId)){
            _paths.add(path);
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        return Evaluation.INCLUDE_AND_CONTINUE;
    }
}
