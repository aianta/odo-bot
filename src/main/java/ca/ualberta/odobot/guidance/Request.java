package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.ControlConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.EventConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import io.vertx.core.http.ServerWebSocket;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

public class Request {

    private static final Logger log = LoggerFactory.getLogger(Request.class);

    private UUID id;

    private String targetNode;

    private String userLocation;

    private String targetMethod;
    private String targetPath;

    public String getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(String targetNode) {
        this.targetNode = targetNode;

        //Fetch identifying properties of the target node.
        try(Transaction tx = LogPreprocessor.graphDB.db.beginTx();
            Result result = tx.execute("match (n:APINode) where n.id = '%s' return n limit 1".formatted(targetNode));
            ResourceIterator<Node> resultIt = result.columnAs("n");
        ){
            Node _targetNode = resultIt.next();
            if(_targetNode == null){
                throw new RuntimeException("Could not retrieve target node with id: " + targetNode);
            }

            this.targetMethod = (String)_targetNode.getProperty("method");
            this.targetPath = (String)_targetNode.getProperty("path");
        };
    }

    public String getUserLocation() {
        return userLocation;
    }

    public void setUserLocation(String userLocation) {
        this.userLocation = userLocation;
    }

    public UUID id(){
        return id;
    }


    public Request(UUID id){
        this.id = id;
    }

    public String getTargetMethod() {
        return targetMethod;
    }

    public String getTargetPath() {
        return targetPath;
    }
}
