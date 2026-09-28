package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.SchemaParameter;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.snippet2xml.SemanticObject;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class QueryDom extends DynamicXPathInstruction implements MultiStepInstruction{

    private static final Logger log = LoggerFactory.getLogger(QueryDom.class);

    public String parameterId;
    public Set<DynamicXPath> dynamicXPaths = new HashSet<DynamicXPath>();
    public String naturalLanguageGuidance;

    private FollowUpContext ctx;

    @Override
    public void bind(FollowUpContext context) {
        this.ctx = context;
    }

    /**
     * queryDom requests will respond with a bunch of HTML elements, from which we need to pick one to convert to a click action.
     */
    @Override
    public void onExecutionResult(JsonObject executionRequest, JsonObject response, Consumer<Instruction> next) {
        if(ctx == null){
            log.error("QueryDom instruction has no follow-up context, cannot handle its execution result.");
            return;
        }

        String sourceNodeId = executionRequest.getString("sourceNodeId");

        List<JsonObject> queryResults = response.getJsonArray("queryResults").stream().map(o->(JsonObject)o).collect(Collectors.toList());

        //If the query failed to find any candidates for OdoBot to consider clicking on, try recovering by recomputing a new path avoiding the node that prompted this.
        if (queryResults.isEmpty()){
            ctx.recoverFromFailedNode(sourceNodeId);
            return;
        }

        log.info("Last result: \n{}", queryResults.get(queryResults.size()-1).getString("html"));

        if(!executionRequest.containsKey("parameterId")){
            ctx.snippet2XML().pickValue(queryResults, ctx.request().getTaskDescription(), executionRequest.getString("naturalLanguageGuidance"))
                    .onSuccess(option->{
                        log.info("Picked option: {}", option);

                        if(option.containsKey("checkboxHTML")){
                            //If the dynamicXpath resolved to a checkbox we have a bit more work to do in determining what the state of the checkbox should be.
                            JsonObject checkboxState = new JsonObject();
                            checkboxState.put("xpath", option.getString("xpath"));
                            checkboxState.put("checked", option.getBoolean("checked"));
                            checkboxState.put("html",  option.getString("html"));
                            ctx.taskPlanner().resolveCheckboxAction(
                                            checkboxState,
                                            ctx.request().getTaskDescription(),
                                            ctx.neo4J().getAssociatedParameterId(sourceNodeId)
                                    ).onFailure(err->log.error(err.getMessage(), err))
                                    .onSuccess(targetCheckboxState->{

                                        if(targetCheckboxState != checkboxState.getBoolean("checked")){
                                            //If the target state of the checkbox is different from its current state. Toggle it.
                                            //Perform a DoClick on the checkbox.
                                            DoClick _instruction = new DoClick();
                                            _instruction.xpath = checkboxState.getString("xpath");
                                            _instruction.setSourceNodeId(sourceNodeId);

                                            ctx.replaceLastInstruction(QueryDom.class, _instruction);

                                            saveQueryDomResult(ctx, executionRequest, response.getJsonArray("queryResults"), sourceNodeId, _instruction.toJson());
                                            next.accept(_instruction);
                                        }else{
                                            log.info("Checkbox does not need to be toggled! Applying NoOP.");
                                            //No change in the checkbox state is needed advance to the next step in the path.
                                            saveQueryDomResult(ctx, executionRequest, response.getJsonArray("queryResults"), sourceNodeId, new JsonObject().put("NOOP", "NOOP"));

                                            //Set the last instruction for all get UIControlState paths to NoOp
                                            ctx.replaceLastInstruction(QueryDom.class, new NoOp());

                                            //Trigger a no-op on the timeline
                                            next.accept(new NoOp());
                                        }

                                    });

                        }else{
                            //Handle click on chosen object.
                            DoClick clickInstruction = new DoClick();
                            clickInstruction.xpath = option.getString("xpath");
                            clickInstruction.setSourceNodeId(sourceNodeId);

                            //Log how this query dom operation went for debugging, troubleshooting and sanity checking.
                            saveQueryDomResult(ctx, executionRequest, response.getJsonArray("queryResults"), sourceNodeId, clickInstruction.toJson());
                            next.accept(clickInstruction);
                        }


                    })
                    .onFailure(err->{
                        log.error("Error while handling queryDom execution result!");
                        log.error(err.getMessage(), err);
                    });
        }else{
            String schemaId = ctx.neo4J().getSchemaId(executionRequest.getString("parameterId"));

            ctx.sqlite().getSemanticSchemaById(schemaId)
                    .compose(schema -> {
                        return Future.join(
                                queryResults.stream()
                                        .map(html->ctx.snippet2XML().getObjectFromHTMLIgnoreSchemaIssues(html.getString("html"), schema).compose(semanticObject -> {
                                            return Future.succeededFuture(new JsonObject().put("semanticObject", semanticObject.toJson()).put("xpath", html.getString("xpath")));
                                        }, err->Future.succeededFuture(null)))
                                        .collect(Collectors.toList())
                        );
                    })
                    .compose(compositeFuture -> {
                        List<JsonObject> objects = compositeFuture.list().stream()
                                .filter(Objects::nonNull) //It's possible some html will fail to resolve to proper xml objects.
                                .map(o->(JsonObject)o).collect(Collectors.toList());

                        Map<String, String> objectMap = new HashMap<>();
                        objects.forEach(object->{
                            SemanticObject semanticObject = new SemanticObject(object.getJsonObject("semanticObject"));
                            objectMap.put(semanticObject.getObject(), object.getString("xpath"));
                        });

                        List<SemanticObject> options = objects.stream().map(json->new SemanticObject(json.getJsonObject("semanticObject"))).collect(Collectors.toList());

                        ExecutionRequest request = ctx.request();

                        return ctx.snippet2XML().pickParameterValue(options, ((SchemaParameter)request.getParameter(executionRequest.getString("parameterId"))).getQuery())
                                //Resolve the picked semantic object to its corresponding xpath...
                                .compose(semanticObject -> Future.succeededFuture(objectMap.get(semanticObject.getObject())))
                                ;

                    })
                    .onSuccess(option->{
                        log.info("Picked option: {}", option);
                        DoClick clickInstruction = new DoClick();
                        clickInstruction.xpath = option;

                        next.accept(clickInstruction);
                    })
                    .onFailure(err->{
                        log.error("Error while handling queryDom execution result!");
                        log.error(err.getMessage(), err);
                    })
            ;
        }
    }

    private static void saveQueryDomResult(FollowUpContext ctx, JsonObject executionRequest, JsonArray queryResults, String sourceNodeId, JsonObject clickRequest){
        String filename = ctx.artifactPath("query-dom-%s.txt".formatted(sourceNodeId));
        File fout = new File(filename);
        try(FileWriter fw = new FileWriter(fout);
            BufferedWriter bw = new BufferedWriter(fw);
        ){
            StringBuilder sb = new StringBuilder();
            sb.append("Source Node ID: %s\n".formatted(sourceNodeId));
            sb.append("Cypher Query:\nMATCH (n) where n.id = '%s' RETURN n;\n".formatted(sourceNodeId));
            sb.append("QueryDom Instruction Execution Request:\n%s\n".formatted(executionRequest.encodePrettily()));
            sb.append("Query Results:\n%s\n".formatted(queryResults.encodePrettily()));
            sb.append("Task Description:\n%s\n".formatted(ctx.request().getTaskDescription()));
            sb.append("Resulting Click Request:\n%s\n".formatted(clickRequest.encodePrettily()));

            bw.write(sb.toString());
            bw.flush();

        }catch(IOException e){
            log.error("Error while saving query dom result!");
            log.error(e.getMessage(), e);

        }
    }

    public boolean equals(Object o){
        if(!(o instanceof QueryDom)){
            return false;
        }

        QueryDom other = (QueryDom) o;

        if(parameterId == null){
            if(other.parameterId != null){
                return false;
            }
            if(dynamicXPath == null && other.dynamicXPath != null){
                return false;
            }

            if(dynamicXPath != null && other.dynamicXPath != null){
                return dynamicXPath.equals(other.dynamicXPath);
            }

        }

        if(dynamicXPaths != null && !dynamicXPaths.isEmpty()){

            return dynamicXPaths.size() == other.dynamicXPaths.size() &&
                    other.dynamicXPaths.containsAll(dynamicXPaths);

        }

        return dynamicXPath.equals(other.dynamicXPath) && parameterId.equals(other.parameterId);
    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(81, 53);

        if(dynamicXPath != null){
            builder.append(dynamicXPath.hashCode());
        }

        if(parameterId != null){
            builder.append(parameterId);
        }


        if(dynamicXPaths != null && !dynamicXPaths.isEmpty()){
            for(DynamicXPath dXpath : dynamicXPaths){
                builder.append(dXpath.hashCode());
            }
        }

        return builder.toHashCode();
    }

    public JsonObject toJson(){
        JsonObject result =
            super.toJson()
            .put("action", "queryDom");

        if(this.parameterId != null){
            result.put("parameterId", this.parameterId);
        }

        //OdoX expects the dynamic xpath to be in the 'xpath' field.
        if(this.dynamicXPaths != null && !dynamicXPaths.isEmpty()){
            result.put("xpath", dynamicXPaths.stream()
                    .map(DynamicXPath::toJson)
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        }else{
            result.put("xpath", this.dynamicXPath.toJson());
        }

        if(this.naturalLanguageGuidance != null){
            result.put("naturalLanguageGuidance", this.naturalLanguageGuidance);
        }

        return result;
    }

    public String toString(){
        if(this.dynamicXPaths != null && !this.dynamicXPaths.isEmpty()){
            StringBuilder sb = new StringBuilder();
            sb.append("Query Dom instruction with %s dynamicXpath(s) [".formatted(this.dynamicXPaths.size()));
            Iterator<DynamicXPath> it = this.dynamicXPaths.iterator();
            while (it.hasNext()){
                DynamicXPath dx = it.next();
                sb.append(dx.toJson().encodePrettily());
                if(it.hasNext()){
                    sb.append(",\n");
                }
            }
            sb.append("]");
            return sb.toString();
        }else{
            return super.toString();
        }

    }
}
