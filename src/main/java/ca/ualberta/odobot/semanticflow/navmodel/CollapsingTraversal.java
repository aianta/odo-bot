package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CollapsingTraversal {

    private static final Logger log = LoggerFactory.getLogger(CollapsingTraversal.class);

    private final GraphDatabaseService db;

    public CollapsingTraversal(GraphDB graphDB){
        this.db = graphDB.db;
    }

    public void collapse(Transaction tx, Node startingNode){



        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .evaluator(Evaluators.all());

        Traverser traverser = traversal.traverse(startingNode);



    }

}
