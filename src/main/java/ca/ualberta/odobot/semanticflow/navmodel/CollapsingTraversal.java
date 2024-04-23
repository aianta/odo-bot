package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class CollapsingTraversal {

    private static final Logger log = LoggerFactory.getLogger(CollapsingTraversal.class);

    private final GraphDatabaseService db;

    public CollapsingTraversal(GraphDB graphDB){
        this.db = graphDB.db;
    }

    public Set<Node> getNodesWithOutDegree(){

        Set<Node> output = new HashSet<>();

        try(
                Transaction tx  = db.beginTx();
                Result result = tx.execute("MATCH (n)-->() with n, count(*) as degree where degree > 1 return n;");
                ResourceIterator<Node> nodes = result.columnAs("n");
        ){

            while (nodes.hasNext()){
                Node node = nodes.next();
                output.add(node);
            }

            output.forEach(node->{
                log.info("Testing collapsing traversal with node {}", node.getElementId());
                this.collapse(tx, node);
            });

        }

        return output;
    }

    public void collapse(Transaction tx, Node startingNode){



        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                .evaluator(Evaluators.all());

        Traverser traverser = traversal.traverse(startingNode);

        Iterator<Path> pathIterator = traverser.iterator();
        while (pathIterator.hasNext()){
            Path p = pathIterator.next();
            log.info("Traverser path: {}", p.toString());
        }



    }

}
