package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.common.Utils;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.ResourceParameter;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;

public class GetDOMSnapshot extends Instruction implements MultiStepInstruction{

    private static final Logger log = LoggerFactory.getLogger(GetDOMSnapshot.class);

    public String parameterName;
    public String parameterId;
    public String normalizedHref;

    private FollowUpContext ctx;

    @Override
    public void bind(FollowUpContext context) {
        this.ctx = context;
    }

    /**
     * GetDOMSnapshot is an instruction that returns a snapshot of the current DOM from OdoX.
     * We send this instruction to resolve resource parameters. When we have a click instruction that is meant to click on a particular kind of resource
     * we first get the DOM snapshot and find all <a> tags linking to that kind of resource on the page. Each such <a> tag is an option for the LLM to consider in the context
     * of the task being executed.
     */
    @Override
    public void onExecutionResult(JsonObject executionRequest, JsonObject response, Consumer<Instruction> next) {
        if(ctx == null){
            log.error("GetDOMSnapshot instruction has no follow-up context, cannot handle its execution result.");
            return;
        }

        log.info("Handling getDOMSnapshot result");

        String domSnapshot = response.getString("domSnapshot");
        String sourceNodeId = response.getString("sourceNodeId");

        ctx.sqlite().getNormalizedHrefsByLabel(executionRequest.getString("parameterName"))
                .compose(labelHrefs -> {

                    //labelHrefs are a set of normalized href values that correspond with this parameter label.
                    //So our next job is to identify all <a> tags whose 'href' attribute values, after normalization match any of the hrefs in the set labelHrefs.
                    Document document = Jsoup.parse(domSnapshot);

                    List<Element> options = new ArrayList<>();

                    document.selectXpath("//a")
                            .stream()
                            .filter(aTag -> aTag.hasAttr("href"))
                            .forEach(aTag->{
                                String normalizedHref = Utils.normalizeBaseUri(aTag.attr("href"));

                                if(labelHrefs.contains(normalizedHref)){
                                    options.add(aTag);
                                }
                            });

                    List<JsonObject> optionsData = options.stream()
                            .map(element-> new JsonObject()
                                    .put("html", element.outerHtml())
                                    .put("xpath", computeXpathNoRoot(element))
                            ).toList();

                    ExecutionRequest request = ctx.request();

                    ResourceParameter resourceParameter = (ResourceParameter)request.getParameter(executionRequest.getString("parameterId"));

                    return Future.all(
                            ctx.snippet2XML().pickResourceParameterValue(optionsData, resourceParameter!=null?resourceParameter.getQuery():request.getTaskDescription(), request.getTaskDescription()),
                            Future.succeededFuture(document)
                    );

                }).onSuccess(compositeFuture->{

                    JsonObject pickedValue = (JsonObject) compositeFuture.list().get(0);
                    Document document = (Document) compositeFuture.list().get(1);


                    Element selectedOption = document.selectXpath(pickedValue.getString("xpath")).get(0);
                    String selectedOptionXpath = pickedValue.getString("xpath");

                    DoClick clickInstruction = new DoClick();
                    clickInstruction.xpath = selectedOptionXpath;
                    clickInstruction.setSourceNodeId(sourceNodeId);

                    next.accept(clickInstruction);
                })
                .onFailure(err->{
                    log.error("Error while handling getDOMSnapshot");
                    log.error(err.getMessage(), err);
                });
    }

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof GetDOMSnapshot)){
            return false;
        }

        GetDOMSnapshot other = (GetDOMSnapshot)o;

        return this.parameterName.equals(other.parameterName);
    }

    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder();
        builder.append(parameterName);
        return builder.toHashCode();
    }

    @Override
    public String toString() {
        return "Get DOMSnapshot to identify options for parameter (%s)".formatted( parameterName);
    }

    public JsonObject toJson(){
        JsonObject json = super.toJson();
        json.put("action", "getDOMSnapshot");
        json.put("parameterName", parameterName);
        json.put("parameterId", parameterId);
        return json;
    }
}
