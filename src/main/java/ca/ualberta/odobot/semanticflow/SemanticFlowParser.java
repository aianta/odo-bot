package ca.ualberta.odobot.semanticflow;



import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

import ca.ualberta.odobot.web.TimelineWebApp;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class SemanticFlowParser extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SemanticFlowParser.class);
    public static final String RDF_REPO_ID = "groundbreaker-1";

    //Timeline data config
    public static final String TIMELINE_DATA_FOLDER = "timelines_apr_28";

    //Set of es-indices to compute timelines for:
//    public static final String [] ES_INDICES = {
//        "dataset-beta-1","dataset-beta-2", "dataset-beta-3","dataset-beta-4", "dataset-beta-5",
//        "dataset-beta-6","dataset-beta-7","dataset-beta-8"
//    };
    public static final String [] ES_INDICES = {
            "dataset-charlie-1","dataset-charlie-3","dataset-charlie-5","dataset-charlie-6",
    };


    @Override
    public Completable rxStart() {
        List<Future> futures = new ArrayList<>();
        for (String index: ES_INDICES){

            EventLogs eventLogs = EventLogs.getInstance();
            List<JsonObject> events = eventLogs.fetchAll(index);
            saveEvents(events, index);
            futures.add(computeTimeline(events, index));
        }

        CompositeFuture.all(futures).onFailure(err->{
            log.error(err.getMessage(), err);
        }).onSuccess(done->{
            //Refresh the web app.
            TimelineWebApp.getInstance().loadTimelinesAndAnnotations();
            log.info("Processed all indices!");
        });

        return super.rxStart();
    }

    public Completable april28thRxStart(){
        EventLogs eventLogs = EventLogs.getInstance();
        List<JsonObject> events = eventLogs.fetchAll(RDF_REPO_ID);

        saveEvents(events, RDF_REPO_ID);


        SemanticSequencer sequencer = new SemanticSequencer();
        try{
            Timeline timeline = sequencer.parse(events, RDF_REPO_ID);
            ListIterator<TimelineEntity> it = timeline.listIterator();
            Map<Integer,List<String>> termManifest = new HashMap<>();
            while (it.hasNext()){
                int index = it.nextIndex();
                TimelineEntity e = it.next();
                List<String> terms = e.terms();
                termManifest.put(index, terms);
            }

            for(int i = 0; i < timeline.size(); i++){
                log.info("{} - terms: {}", i, termManifest.get(i));
            }



            log.info("Timeline: {}", timeline.toString());

            saveTimeline(timeline);

            //Let's try to create a state model!
//            SimpleStateModelParser stateModelParser = new SimpleStateModelParser();
//            stateModelParser.parseTimeline(timeline);

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }



        //Refresh the web app.
        TimelineWebApp.getInstance().loadTimelinesAndAnnotations();

        return super.rxStart();
    }

    public Future<Timeline> computeTimeline(List<JsonObject> events , String elasticSearchIndex){
        Promise<Timeline> promise = Promise.promise();

        SemanticSequencer sequencer = new SemanticSequencer();
        try{
            Timeline timeline = sequencer.parse(events, elasticSearchIndex);
            ListIterator<TimelineEntity> it = timeline.listIterator();
            Map<Integer,List<String>> termManifest = new HashMap<>();
            while (it.hasNext()){
                int index = it.nextIndex();
                TimelineEntity e = it.next();
                List<String> terms = e.terms();
                termManifest.put(index, terms);
            }

            for(int i = 0; i < timeline.size(); i++){
                log.info("{} - terms: {}", i, termManifest.get(i));
            }

            saveTimeline(timeline);
            promise.complete(timeline);
        }catch (Exception e){
            log.error(e.getMessage(),e);
        }

        return promise.future();
    }


    public void saveTimeline(Timeline timeline){
        try{
            Path rootDir = Path.of(TIMELINE_DATA_FOLDER);

            //Create timelines folder if it doesn't exist
            if(!Files.exists(rootDir) ) Files.createDirectory(Path.of(TIMELINE_DATA_FOLDER));

            //Create a folder for this timeline using its id if it doesn't exist.
            Path timelineDir = Path.of(TIMELINE_DATA_FOLDER, timeline.getId().toString());
            if(!Files.exists(timelineDir)) Files.createDirectory(timelineDir);

            File timelineFile = new File(timelineDir + "/timeline.json");
            try(FileWriter fw = new FileWriter(timelineFile);
                BufferedWriter bw = new BufferedWriter(fw);
            ){
                bw.write(timeline.toJson().encodePrettily());
                bw.flush();
            }

            File annotationsFile = new File(timelineDir + "/annotations.json");
            try(FileWriter fw = new FileWriter(annotationsFile);
                BufferedWriter bw = new BufferedWriter(fw);
            ){
                bw.write(timeline.getAnnotations().encodePrettily());
                bw.flush();
            }

        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
        }


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

    public static void saveEvents(List<JsonObject> events, String esIndex){
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        File out = new File(esIndex + "-" + dateFormat.format(Date.from(Instant.now()))+".json");
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
