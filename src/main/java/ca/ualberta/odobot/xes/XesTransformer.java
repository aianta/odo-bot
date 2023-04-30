package ca.ualberta.odobot.xes;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.deckfour.xes.factory.XFactoryBufferedImpl;
import org.deckfour.xes.id.XID;
import org.deckfour.xes.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class XesTransformer {

    private static final Logger log = LoggerFactory.getLogger(XesTransformer.class);
    private static final XFactoryBufferedImpl factory = new XFactoryBufferedImpl();


    public XTrace parseTimeline(JsonObject timeline){
        //Define trace attributes
        XAttributeID id = factory.createAttributeID("id", XID.parse(timeline.getString("id")),null);

        //Create trace attribute map
        XAttributeMap traceAttributes = factory.createAttributeMap();
        traceAttributes.put("id", id);

        //Create trace
        XTrace trace = factory.createTrace();
        trace.setAttributes(traceAttributes);

        JsonArray entities = timeline.getJsonArray("data");
        entities.stream().map(o->(JsonObject)o)
                .map(entity->{
                    XAttributeID eventId = factory.createAttributeID("id", XID.parse(UUID.randomUUID().toString()), null);
                    XAttributeLiteral timelineId = factory.createAttributeLiteral("_id", entity.getString("id"), null);

                    XAttributeMap eventAttributes = factory.createAttributeMap();
                    XEvent event = factory.createEvent();
                    return event;
                });

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
