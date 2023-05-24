package ca.ualberta.odobot.logpreprocessor;

public class Constants {

    public static final String TIMELINE_SERVICE_ADDRESS = "timeline-service";
    public static final String ELASTICSEARCH_SERVICE_ADDRESS = "elasticsearch-service";
    public static final String DEEP_SERVICE_HOST = "172.21.18.184";
    public static final int DEEP_SERVICE_PORT = 5000;
    public static final String DEEP_SERVICE_ACTIVITY_LABELS_ENDPOINT = "/activitylabels/";
    public static final String DEEP_SERVICE_MODEL_ENDPOINT = "/model/";
    public static final String TIMESTAMP_FIELD = "timestamps_eventTimestamp";
    public static final String PREPROCESSING_DATA_DIR = "/preprocessing_data/";
    public static final String EXECUTIONS_INDEX = "preprocessing-pipeline-executions";
    public static final String PIPELINES_INDEX = "preprocessing-pipelines";
}
