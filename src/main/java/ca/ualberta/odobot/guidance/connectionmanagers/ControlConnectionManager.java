package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.execution.ExecutionParameter;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.stream.Collectors;

public class ControlConnectionManager extends AbstractConnectionManager implements ConnectionManager{

    private static final Logger log = LoggerFactory.getLogger(ControlConnectionManager.class);

    public ControlConnectionManager(OdoClient client){
        super(client);
    }


    /**
     * Handles the types of messages the server receives on the control socket.
     * @param message
     */
    public void onMessage(JsonObject message){
        log.info("ControlConnectionManager onMessage invoked! - {}", message.getString("type"));
        switch (message.getString("type")){
            case "PATHS_REQUEST":
                ExecutionRequest _request = new ExecutionRequest();
                _request.setId(UUID.fromString(message.getString("pathsRequestId")));
                _request.setTarget(message.getString("targetNode"));
                _request.setUserLocation(message.getString("userLocation"));
                //TODO -> do we want/need to support this avenue for requesting executions?
//                Request request = new Request(UUID.fromString(message.getString("pathsRequestId")));
//                request.setTargetNode(message.getString("targetNode"));
//                request.setUserLocation(message.getString("userLocation"));

                //client.getRequestManager().addNewRequest(request);


                break;
            case "EXECUTION_REQUEST":

                //Read execution request data and initialize an execution request object.
                ExecutionRequest executionRequest = new ExecutionRequest();
                executionRequest.setId(UUID.fromString(message.getString("id")));
                executionRequest.setTarget(UUID.fromString(message.getString("target")));
                executionRequest.setUserLocation(message.getString("userLocation"));

                JsonArray parameters = message.getJsonArray("parameters");
                executionRequest.setParameters(parameters.stream()
                        .map(o->(JsonObject)o) //Type cast everything to json objects
                        .map(ExecutionParameter::fromJson)
                        .collect(Collectors.toList()));

                client.getRequestManager().addNewRequest(executionRequest);

                break;
            case "STOP_GUIDANCE_REQUEST":

                //TODO -> What, if any, responsibilities does the server have in this case.
                break;
        }
    }



}
