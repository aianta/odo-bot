package ca.ualberta.odobot.logpreprocessor.xes;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.deckfour.xes.extension.std.XConceptExtension;
import org.deckfour.xes.factory.XFactory;
import org.deckfour.xes.factory.XFactoryBufferedImpl;
import org.deckfour.xes.factory.XFactoryNaiveImpl;
import org.deckfour.xes.id.XID;
import org.deckfour.xes.model.*;
import org.deckfour.xes.out.XesXmlSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public class XesTransformer {

    private static final Logger log = LoggerFactory.getLogger(XesTransformer.class);
    private static final XFactory factory = new XFactoryNaiveImpl();

//    private static final XFactoryBufferedImpl factory = new XFactoryBufferedImpl();

    public void save(XLog xLog, File output ){
        XesXmlSerializer serializer = new XesXmlSerializer();
        try(FileOutputStream fos = new FileOutputStream(output)){
            serializer.serialize(xLog, fos);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    /**
     * Parses a set of timelines to produce an XLog.
     * @param timelines
     * @param activityLabelMappings
     * @return
     */
    public XLog parse(JsonArray timelines, JsonObject activityLabelMappings){
        XLog xlog = factory.createLog();

        timelines.stream().map(o->(JsonObject)o)
                .map(timelineJson->parse(timelineJson, activityLabelMappings))
                .forEach(xTrace->xlog.add(xTrace));

        return xlog;
    }

    /**
     * Parses an individual timeline to produce an XTrace.
     * @param timeline
     * @param activityLabelMappings
     * @return
     */
    public XTrace parse(JsonObject timeline, JsonObject activityLabelMappings){
        //Define trace attributes
        XAttributeID id = factory.createAttributeID("id", XID.parse(timeline.getString("id")),null);
        //TODO -> This should be the actual session id, which, currently isn't included in the timeline json. Need to fix this.
        //TODO -> As of May 18, 2023: DO NOT FIX THIS, session ids may be broken, use timeline id's for now!!
        XAttributeLiteral caseId = factory.createAttributeLiteral("sessionId", timeline.getString("id"), XConceptExtension.instance());

        //Create trace attribute map
        XAttributeMap traceAttributes = factory.createAttributeMap();
        traceAttributes.put("id", id);
        traceAttributes.put("caseId", caseId);

        //Create trace
        XTrace trace = factory.createTrace();
        trace.setAttributes(traceAttributes);

        JsonArray entities = timeline.getJsonArray("data");
        entities.stream().map(o->(JsonObject)o)
                .map(entity->{
                    XAttributeID eventId = factory.createAttributeID("id", XID.parse(UUID.randomUUID().toString()), null);
                    XAttributeLiteral timelineId = factory.createAttributeLiteral("_t_id", entity.getString("id"), null);
                    XAttributeTimestamp timestamp = factory.createAttributeTimestamp("timestamp", entity.getLong("timestamp_milli"), null);
                    XAttributeLiteral activity = factory.createAttributeLiteral("activity", activityLabelMappings.getJsonObject("mappings").getString(entity.getString("id")), XConceptExtension.instance());


                    XAttributeMap eventAttributes = factory.createAttributeMap();
                    eventAttributes.put("eventId", eventId);
                    eventAttributes.put("timelineId", timelineId);
                    eventAttributes.put("timestamp", timestamp);
                    eventAttributes.put("activity", activity);

                    XEvent event = factory.createEvent();
                    event.setAttributes(eventAttributes);

                    return event;
                }).forEach(trace::insertOrdered);

        return trace;
    }


    public static void main(String args []){
        XFactoryBufferedImpl factory = new XFactoryBufferedImpl();
        XLog log = factory.createLog();

        XTrace trace = factory.createTrace();
        XEvent event = factory.createEvent();
        XAttributeMap attributeMap = factory.createAttributeMap();


        log.add(trace);
    }
}
