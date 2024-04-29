package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CollapsingTraversal {

    private static final Logger log = LoggerFactory.getLogger(CollapsingTraversal.class);

    private final GraphDatabaseService db;

    public CollapsingTraversal(GraphDB graphDB){
        this.db = graphDB.db;
    }


    public List<Collapse> doCollapsePass(){
        List<Collapse> collapses = new ArrayList<>();
        Collapse collapse = null;

        do{
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


                 collapse = output.stream()
                        .peek(node->log.info("Searching for collapsable patterns with traversal starting from {}", node.getElementId()))
                        .map(node->this.findCollapse(tx, node))
                        .filter(Objects::nonNull)
                        .findAny()
                        .orElse(null)
                        ;



                if(collapse == null){
                    log.info("No collapsable patterns found!");
                    break;
                }

                applyCollapse(tx, collapse);

                collapses.add(collapse);

                tx.commit(); //Commit and finish the transaction.

            }

        }while (collapse != null);


        return collapses;

    }

    private void applyCollapse(Transaction tx, Collapse collapse){

        Iterator<List<CollapsingEvaluator.PathElement>> mergeIt = collapse.mergableIterator();

        //Get the starting anchor
        Node startingAnchor = tx.getNodeByElementId(collapse.startingAnchor().elementId);
        Node endingAnchor = tx.getNodeByElementId(collapse.endingAnchor().elementId);

        List<Node> collapsedNodes = new ArrayList<>();

        while (mergeIt.hasNext()){

            List<CollapsingEvaluator.PathElement> mergeList = mergeIt.next();
            Set<Node> nodeSet = new HashSet<>();

            mergeList.forEach(pathElement -> nodeSet.add(tx.getNodeByElementId(pathElement.elementId)));

            String baseLabel = BaseLabel.resolveBaseLabel(nodeSet.iterator().next());

            log.info("Creating collapsed node for {} label", baseLabel);

            CollapsedNode collapsedNode = switch (baseLabel){
                case "ClickNode" -> new CollapsedClickNode(nodeSet);
                case "DataEntryNode" -> new CollapsedDataEntryNode(nodeSet);
                case "EffectNode" -> new CollapsedEffectNode(nodeSet);
                default -> throw new RuntimeException("Uncollapsable node set!");
            };

            StringBuilder sb = new StringBuilder();
            nodeSet.forEach(n->sb.append(String.format("%s", n.getElementId())));
            log.info("Collapsing [{}] into a single node {}!", sb.toString(), collapsedNode.id.toString());

            //Delete the nodes in the node set.
            nodeSet.forEach(node ->tx.execute("MATCH (n) WHERE elementId(n) = '%s' detach delete n".formatted(node.getElementId())));

            //Create the collapsed node
            Node replacement = collapsedNode.createNode(tx);

            collapsedNodes.add(replacement);
        }

        //Link the replacement nodes together in the appropriate order.
        ListIterator<Node> it = collapsedNodes.listIterator();
        String query = "MATCH (n), (m) WHERE elementId(n) = '%s' AND elementId(m) = '%s' CREATE (n)-[:NEXT]->(m) ;";
        Node lastNode = null;
        while (it.hasNext()){

            Node curr = it.next();

            log.info("index: {}", it.previousIndex());

            if(it.previousIndex() == 0){ //If this is the first collapsed node, create an edge from the anchor
                tx.execute(query.formatted(startingAnchor.getElementId(), curr.getElementId()));
            }else{
                //Otherwise create an edge from the last node to the current node.
                tx.execute(query.formatted(lastNode.getElementId(), curr.getElementId()));
            }

            lastNode = curr;
        }

        //Create an edge from the last node to the end anchor.
        tx.execute(query.formatted(lastNode.getElementId(), endingAnchor.getElementId()));


    }

    private Collapse findCollapse(Transaction tx, Node startingNode){

        CollapsingEvaluator evaluator = new CollapsingEvaluator();

        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                .evaluator(evaluator);

        Traverser traverser = traversal.traverse(startingNode);

        Iterator<Path> pathIterator = traverser.iterator();
        while (pathIterator.hasNext()){
            pathIterator.next();
            //log.info("Traverser path: {}", p.toString());
        }

        log.info("Collapse is: {}", evaluator.hasCollapse()?evaluator.getCollapse():"null");

        return evaluator.getCollapse();

    }

}
