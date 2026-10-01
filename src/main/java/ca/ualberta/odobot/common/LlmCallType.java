package ca.ualberta.odobot.common;

/**
 * The contexts in which an LLM is called, used to break token usage down by call type.
 */
public enum LlmCallType {

    //Charted agent, task interpretation
    TASK_REWRITE("task-rewrite", Kind.CHAT_COMPLETION),
    SIMILAR_TASK_EMBEDDING("similar-task-embedding", Kind.EMBEDDING),
    SIMILAR_TASK_PICK("similar-task-pick", Kind.CHAT_COMPLETION),
    TARGET_API_CALL_PICK("target-api-call-pick", Kind.CHAT_COMPLETION),

    //Charted agent, path selection
    PATH_SELECTION("path-selection", Kind.CHAT_COMPLETION),

    //Charted agent, data entry and UI controls
    TEXT_INPUT_VALUE("text-input-value", Kind.CHAT_COMPLETION),
    SELECT_OPTION("select-option", Kind.CHAT_COMPLETION),
    CHECKBOX_STATE("checkbox-state", Kind.CHAT_COMPLETION),
    RADIO_OPTION("radio-option", Kind.CHAT_COMPLETION),

    //Charted agent, DOM queries
    ELEMENT_PICK("element-pick", Kind.CHAT_COMPLETION),
    HTML_TO_XML("html-to-xml", Kind.CHAT_COMPLETION),
    PARAMETER_OBJECT_PICK("parameter-object-pick", Kind.CHAT_COMPLETION),
    RESOURCE_LINK_PICK("resource-link-pick", Kind.CHAT_COMPLETION),

    //Uncharted agent
    UNCHARTED_STEP("uncharted-step", Kind.CHAT_COMPLETION),

    //Calls made outside the agents' contexts (model construction, data entry labelling, ...)
    OTHER_CHAT_COMPLETION("other-chat-completion", Kind.CHAT_COMPLETION),
    OTHER_EMBEDDING("other-embedding", Kind.EMBEDDING);

    public enum Kind {
        CHAT_COMPLETION("chat_completion"),
        EMBEDDING("embedding");

        public final String label;

        Kind(String label){
            this.label = label;
        }
    }

    public final String label;
    public final Kind kind;

    LlmCallType(String label, Kind kind){
        this.label = label;
        this.kind = kind;
    }
}
