package ca.ualberta.odobot.logpreprocessor;

public class Constants {

    public static final String SQLITE_CONNECTION_STRING = "jdbc:sqlite:odobot.db";
    public static final String TPG_SERVICE_ADDRESS = "tpg-service";
    public static final String SQLITE_SERVICE_ADDRESS = "sqlite-service";
    public static final String SNIPPET_2_XML_SERVICE_ADDRESS = "snippet2xml-service";
    public static final String DOMSEQUENCING_SERVICE_ADDRESS = "domsequencing-service";
    public static final String ELASTICSEARCH_SERVICE_ADDRESS = "elasticsearch-service";

    public static final String TASK_PLANNER_SERVICE_ADDRESS = "task-planner-service";
    public static final String DATA_ENTRY_2_LABEL_SERVICE_ADDRESS = "dataentry2label-service";
    public static final String SNIPPET_EXTRACTOR_SERVICE_ADDRESS = "snippet-extractor-service";
    public static final String DEEP_SERVICE_HOST = "172.31.208.70";
    public static final int DEEP_SERVICE_PORT = 5000;
    public static final String DEEP_SERVICE_ACTIVITY_LABELS_ENDPOINT = "/activitylabels/";
    public static final String DEEP_SERVICE_MODEL_ENDPOINT = "/model/";
    public static final String TIMESTAMP_FIELD = "timestamps_eventTimestamp";
    public static final String EXECUTIONS_INDEX = "preprocessing-pipeline-executions";
    public static final String PIPELINES_INDEX = "preprocessing-pipelines";
    public static final String ROOT_DATA_DIR = "data_artifacts";
    public static final String BPMN_KEY = "bpmn";
    public static final String TREE_KEY = "tree";
    public static final String DFG_KEY = "dfg";
    public static final String PETRI_KEY = "petri";
    public static final String TRANSITION_KEY = "transition";
    public static final String BPMN_FILE_NAME = "bpmn.png";
    public static final String TREE_FILE_NAME = "tree.png";
    public static final String DFG_FILE_NAME = "dfg.png";
    public static final String PETRI_FILE_NAME = "petri.png";
    public static final String TRANSITION_FILE_NAME = "transition.png";
    public static final String XES_FILE_NAME = "log.xes";
    public static final String EXECUTION_FILE_NAME = "execution.json";
    public static final String CLUSTERING_RESULTS_FIELD_PREFIX = "clustering_results_";
    public static final String DEEP_SERVICE_ACTIVITY_LABELS_V2_ENDPOINT = "/activitylabels/v2/";
    public static final String DEEP_SERVICE_ACTIVITY_LABELS_V3_ENDPOINT = "/activitylabels/v3/";
    public static final String DEEP_SERVICE_ACTIVITY_LABELS_V4_ENDPOINT = "/activitylabels/v4/";
}
