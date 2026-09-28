package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class GetUIControlState extends XPathInstruction implements MultiStepInstruction {

    private static final Logger log = LoggerFactory.getLogger(GetUIControlState.class);

    public enum Type {
        TEXT,
        CHECKBOX,
        RADIO_BUTTON,
        SELECT,
        INPUT_COMBO_BOX,
        TINY_MCE_EDITOR
    }

    public Type type;
    public String editorId;
    public String parameterId;

    private FollowUpContext ctx;

    @Override
    public void bind(FollowUpContext context) {
        this.ctx = context;
    }

    /**
     * Resolves the value to enter/select/check for the control, then produces the instruction that applies it.
     */
    @Override
    public void onExecutionResult(JsonObject executionRequest, JsonObject response, Consumer<Instruction> next) {
        if(ctx == null){
            log.error("GetUIControlState instruction has no follow-up context, cannot handle its execution result.");
            return;
        }

        log.info("Received control state: \n {}\n for instruction with sourceNodeId: {}",response.encodePrettily(), executionRequest.getString("sourceNodeId"));

        GetUIControlState.Type type = GetUIControlState.Type.valueOf(response.getString("uiControlType"));
        JsonArray state = response.getJsonArray("state");

        switch (type){
            case TEXT -> {
                JsonObject _state = state.getJsonObject(0);
                ctx.taskPlanner().resolveDataEntryValue(
                        ctx.request().getTaskDescription(),
                        executionRequest.getString("parameterId"),
                        _state.getString("value")
                ).onFailure(err->log.error(err.getMessage(), err))
                        .onSuccess(inputData->{

                            //Create an instruction object to update the nav path's last instruction.
                            EnterData _instruction = new EnterData();
                            _instruction.xpath = _state.getString("xpath");
                            _instruction.setSourceNodeId(executionRequest.getString("sourceNodeId"));
                            _instruction.data = inputData;
                            _instruction.parameterId = executionRequest.getString("parameterId");

                            //TODO: is checking that the last instruction is a GetUIControlState sufficient?
                            ctx.replaceLastInstruction(GetUIControlState.class, _instruction);

                            next.accept(_instruction);
                        });
            }
            case SELECT -> {

                ctx.taskPlanner().resolveSelectAction(state, ctx.request().getTaskDescription(), executionRequest.getString("parameterId"))
                        .onFailure(err->log.error(err.getMessage(), err))
                        .onSuccess(selectedOption->{

                            //Create an instruction object to update the nav path's last instruction.
                            SelectOption _instruction = new SelectOption();
                            _instruction.xpath = state.getJsonObject(0).getString("xpath");
                            _instruction.setSourceNodeId(executionRequest.getString("sourceNodeId"));
                            _instruction.value = selectedOption.getString("value");
                            _instruction.parameterId = executionRequest.getString("parameterId");

                            ctx.replaceLastInstruction(GetUIControlState.class, _instruction);

                            next.accept(_instruction);
                        });
            }
            case CHECKBOX -> {

                ctx.taskPlanner().resolveCheckboxAction(
                        state.getJsonObject(0),
                        ctx.request().getTaskDescription(),
                        executionRequest.getString("parameterId")
                ).onFailure(err->log.error(err.getMessage(), err))
                        .onSuccess(targetCheckboxState->{

                            //Determine if the checkbox' state needs to change.
                            if(targetCheckboxState != state.getJsonObject(0).getBoolean("checked")){

                                //If the target state of the checkbox is different from its current state. Toggle it.
                                //Perform a DoClick on the checkbox.
                                DoClick _instruction = new DoClick();
                                _instruction.xpath = state.getJsonObject(0).getString("xpath");
                                _instruction.setSourceNodeId(executionRequest.getString("sourceNodeId"));

                                ctx.replaceLastInstruction(GetUIControlState.class, _instruction);

                                next.accept(_instruction);

                            }else{
                                //No change in the checkbox state is needed advance to the next step in the path.

                                //Set the last instruction for all get UIControlState paths to NoOp
                                ctx.replaceLastInstruction(GetUIControlState.class, new NoOp());

                                //Trigger a no-op on the timeline
                                next.accept(new NoOp());
                            }

                        });

            }
            case RADIO_BUTTON -> {

                ctx.taskPlanner().resolveRadioButtonAction(
                        state,
                        ctx.request().getTaskDescription(),
                        executionRequest.getString("parameterId")
                ).onFailure(err->log.error(err.getMessage(), err))
                        .onSuccess(selectedButton->{

                            //Create an instruction object to update the nav paths's last instruction so instruction watcher can match it properly.
                            //The instruction we want to report to instruction watcher is an EnterData instruction, because the click on the radio button will be observed as a DataEntry.
                            EnterData _mockInstruction = new EnterData(); //We use an EnterData instruction here just to hold the alternate xpath for the radio button, since the instruction watcher only checks for matching xpaths when identifying whether an instruction was executed or not, and doesn't actually check that the instruction is an instance of ClickInstruction. This is a bit hacky but it works given the current implementation of the instruction watcher. A more robust long-term solution would be to refactor the instruction watcher to check for matching instruction types as well as matching xpaths.
                            _mockInstruction.xpath = selectedButton.getString("xpath");
                            _mockInstruction.setSourceNodeId(executionRequest.getString("sourceNodeId"));

                            ctx.replaceLastInstruction(GetUIControlState.class, _mockInstruction);

                            //The instruction we actually want to execute is a click.
                            DoClick _instruction = new DoClick();
                            _instruction.xpath = selectedButton.getString("xpath");
                            _instruction.setSourceNodeId(executionRequest.getString("sourceNodeId"));

                            next.accept(_instruction);

                        });

            }
            case TINY_MCE_EDITOR -> {
                JsonObject _state = state.getJsonObject(0);
                ctx.taskPlanner().resolveDataEntryValue(
                                ctx.request().getTaskDescription(),
                                executionRequest.getString("parameterId"),
                                _state.getString("value")
                        ).onFailure(err->log.error(err.getMessage(), err))
                        .onSuccess(inputData->{

                            //Create an instruction object to update the nav path's last instruction.
                            EnterDataTinymce _instruction = new EnterDataTinymce();
                            _instruction.xpath = _state.getString("xpath");
                            _instruction.editorId = _state.getString("id");
                            _instruction.setSourceNodeId(executionRequest.getString("sourceNodeId"));
                            _instruction.data = inputData;
                            _instruction.parameterId = executionRequest.getString("parameterId");

                            //TODO: is checking that the last instruction is a GetUIControlState sufficient?
                            ctx.replaceLastInstruction(GetUIControlState.class, _instruction);

                            next.accept(_instruction);
                        });
            }
            case INPUT_COMBO_BOX -> {}
        }
    }

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof GetUIControlState)){
            return false;
        }

        GetUIControlState other = (GetUIControlState) o;

        if (this.editorId != null) {
            return other.editorId.equals(this.editorId);
        }

        return this.type == other.type && this.xpath.equals(other.xpath);
    }

    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder(71, 53);
        if (this.editorId != null) {
            builder.append(this.editorId);
        }else{
            builder.append(this.xpath);
            builder.append(this.type.name());
        }
        return builder.toHashCode();
    }

    public JsonObject toJson(){
        JsonObject json = super.toJson();
        json.put("action", "getUIControlState");
        json.put("xpath", this.xpath);
        json.put("uiControlType", this.type.name());

        if(parameterId != null){
            json.put("parameterId", parameterId);
        }

        if(editorId != null){
            json.put("editorId", editorId);
        }
        return json;
    }


}
