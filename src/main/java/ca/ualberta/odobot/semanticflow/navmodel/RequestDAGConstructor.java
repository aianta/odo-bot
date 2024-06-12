package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The RequestDAGConstructor is responsible for returning a Directed Acyclic Graph consisting of the
 * paths from the user's location at request-time to the desired target API Node.
 */
public class RequestDAGConstructor {

    private static final Logger log = LoggerFactory.getLogger(RequestDAGConstructor.class);
    private final GraphDatabaseService db;

    public RequestDAGConstructor(GraphDB graphDB){
        this.db = graphDB.db;
    }

    public Set<NavPath> construct(UUID src, UUID tgt){

        try(Transaction tx = db.beginTx();
            Result result = tx.execute("MATCH (src), (tgt) WHERE src.id = '%s' AND tgt.id = '%s' RETURN src, tgt; ".formatted(src.toString(), tgt.toString()));
            ResourceIterator<Node> srcIterator = result.<Node>columnAs("src");
            ResourceIterator<Node> tgtIterator = result.<Node>columnAs("tgt");
        ){
            if(!srcIterator.hasNext()){
                throw new NotFoundException("src node with id %s could not be found".formatted(src.toString()));
            }

            if(!tgtIterator.hasNext()){
                throw new NotFoundException("tgt node with id %s could not be found".formatted(tgt.toString()));
            }

            Node srcNode = srcIterator.next();
            Node tgtNode = tgtIterator.next();


            TraversalDescription traversal = tx.traversalDescription()
                    .breadthFirst()
                    .uniqueness(Uniqueness.NODE_PATH)
                    .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                    .evaluator(Evaluators.endNodeIs(
                            Evaluation.INCLUDE_AND_PRUNE,
                            Evaluation.INCLUDE_AND_CONTINUE,
                            tgtNode
                            ));

            Traverser traverser = traversal.traverse(srcNode);

            Iterator<Path> it = traverser.iterator();

            LinkedHashSet<NavPath> navPaths = new LinkedHashSet<>();

            while (it.hasNext()){
                NavPath navPath = new NavPath();
                navPath.setPath(it.next());
                navPaths.add(navPath);
            }

            return navPaths;
        }
    }


}
