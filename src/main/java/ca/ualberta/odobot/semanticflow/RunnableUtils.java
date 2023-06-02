package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A collection of functions useful for development/analysis/etc.
 */
public class RunnableUtils {

    private static final Logger log = LoggerFactory.getLogger(RunnableUtils.class);
    private static String DATA_PATH = "listener-test-1-02-05-2023.json";

    public static void main(String args []){

        List<JsonObject> events = loadEvents(DATA_PATH);
        events.stream().filter(e->e.containsKey("eventDetails_domSnapshot"))
                .map(e->new JsonObject(e.getString("eventDetails_domSnapshot")))
                .map(e->e.getString("outerHTML"))
                .forEach(log::info);



//        printTimeline(events, "groundbreaker-1");


    }

    /**
     * Outputs a timeline file that looks like this:
     *
     * <timestamps_sinceSessionStartMillis> <mongo_id> -> interaction event <eventDetails_name> <eventDetails_xpath>
     * <timestamps_sinceSessionStartMillis> \t <mongo_id> -> DOM_EFFECT (<action>)
     *
     * @param events list of JSON Objects with events.
     */
    public static void printTimeline(List<JsonObject> events, String outputFileName){

        File out = new File(outputFileName + ".timeline");
        try(FileWriter fw = new FileWriter(out);
            BufferedWriter bw = new BufferedWriter(fw)
        ){

            events.forEach(json->{
                try{
                    StringBuilder sb;
                    switch (json.getString("eventType")){
                        case "customEvent":
                            sb = new StringBuilder();
                            sb.append(json.getString("timestamps_eventTimestamp"));
                            sb.append("\t");
                            sb.append(json.getString("mongo_id"));
                            sb.append("\t");
                            sb.append(json.getString("eventDetails_name"));
                            sb.append(" (");
                            sb.append(json.getString("eventDetails_action"));
                            sb.append(")\n");
                            bw.write(sb.toString());
                            break;
                        case "interactionEvent":
                            sb = new StringBuilder();
                            sb.append(json.getString("timestamps_eventTimestamp"));
                            sb.append(" ");
                            sb.append(json.getString("mongo_id"));
                            sb.append(" -> ");
                            sb.append(json.getString("eventType"));
                            sb.append("\t");
                            sb.append(json.getString("eventDetails_name"));
                            sb.append("\n");
                            bw.write(sb.toString());
                            break;
                    }
                }catch (IOException ioException){
                    log.error(ioException.getMessage(), ioException);
                }


            });

        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

    }

    public static List<JsonObject> loadEvents(String path){
        File in = new File(path);
        try(FileReader fr = new FileReader(in);
            BufferedReader br = new BufferedReader(fr)){

            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            do {
                sb.append(line);
                line = br.readLine();
            }while (line != null);

            return new JsonArray(sb.toString()).stream().map(o->(JsonObject)o).collect(Collectors.toList());

        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
        }
        log.info("Failed to load events");
        return new ArrayList<>();
    }
}
