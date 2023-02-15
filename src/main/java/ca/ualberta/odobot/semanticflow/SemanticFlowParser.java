package ca.ualberta.odobot.semanticflow;


import ca.ualberta.odobot.semanticflow.extraction.terms.impl.TextStrategy;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.DistanceToTarget;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.*;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class SemanticFlowParser extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SemanticFlowParser.class);
    private static final String RDF_REPO_ID = "semantic-timeline-4";

    @Override
    public Completable rxStart() {

        EventLogs eventLogs = EventLogs.getInstance();
        List<JsonObject> events = eventLogs.fetchAll(RDF_REPO_ID);

        saveEvents(events);


        SemanticSequencer sequencer = new SemanticSequencer();
        Timeline timeline = sequencer.parse(events);
        ListIterator<TimelineEntity> it = timeline.listIterator();
        Map<Integer,List<String>> termManifest = new HashMap<>();
        while (it.hasNext()){
            int index = it.nextIndex();
            TimelineEntity e = it.next();
            List<String> terms = e.terms(new NoRanking(), new TextStrategy());
            termManifest.put(index, terms);
        }

        for(int i = 0; i < timeline.size(); i++){
            log.info("{} - terms: {}", i, termManifest.get(i));
        }



        log.info("Timeline: {}", timeline.toString());

        return super.rxStart();
    }

    /**
     * Helper method for reading only a particular part of a trace.
     * @param events
     * @param startMongoId
     * @param endMongoId
     * @return
     */
    public List<JsonObject> eventsSubset(List<JsonObject> events, String startMongoId, String endMongoId){
        //We want to focus on a particular set of events that follow from the create new event link
        List<JsonObject> specificEvents = new ArrayList<>();
        boolean include = false;
        for(JsonObject event: events){
            if(event.getString("mongo_id").equals(startMongoId)){
                include = true;
            }
            if(event.getString("mongo_id").equals(endMongoId)){
                include = false;
            }
            if(include){
                specificEvents.add(event);
            }
        }
        return specificEvents;
    }

    public void flowParse(List<JsonObject> events){
        log.info("Building flows model");
        FlowParser parser = new FlowParser.Builder()
                .setNamespace("http://localhost:8080/rdf-server/repositories/"+RDF_REPO_ID+"#")
                .build();

        Model flows = parser.parse(events);

        String serverUrl = "http://localhost:8080/rdf4j-server";
        log.info("Sending flows model to {}", serverUrl);

        RemoteRepositoryManager manager = new RemoteRepositoryManager(serverUrl);
        manager.init();

        Repository repo = manager.getRepository(RDF_REPO_ID);
        try(RepositoryConnection conn = repo.getConnection()){
            conn.add(flows);
        }

        log.info("done");
    }

    public static void saveEvents(List<JsonObject> events){
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        File out = new File(RDF_REPO_ID + "-" + dateFormat.format(Date.from(Instant.now()))+".json");
        try(FileWriter fw = new FileWriter(out);
            BufferedWriter bw = new BufferedWriter(fw)){

            JsonArray json = events.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            bw.write(json.encodePrettily());

            bw.flush();
        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
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
