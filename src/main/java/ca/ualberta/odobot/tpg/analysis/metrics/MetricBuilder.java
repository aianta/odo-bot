package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A wrapper class for all metrics sent to Elasticsearch regarding TPG Training/Test tasks, etc.
 */
public class MetricBuilder {

    private static final DateTimeFormatter metricTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSZ");

    private static final Logger log = LoggerFactory.getLogger(MetricBuilder.class);


    List<MetricComponent> components = new ArrayList<>();

    List<JsonObject> customComponents = new ArrayList<>();

    public MetricBuilder addCustomComponent(JsonObject object){
        customComponents.add(object);
        return this;
    }

    public MetricBuilder addComponent(MetricComponent component){
        components.add(component);
        return this;
    }


    public JsonObject build(){
        JsonObject metric = new JsonObject()
                .put("id", UUID.randomUUID().toString())
                .put("timestamp", ZonedDateTime.now().format(metricTimeFormatter))
                ;

        customComponents.forEach(json->metric.mergeIn(json));
        components.forEach(component->metric.mergeIn(component.toJson()));
        return metric;
    }

}
