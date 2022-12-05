package ca.ualberta.odobot.semanticflow;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SemanticFlowParser extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SemanticFlowParser.class);
    private static final String RDF_REPO_ID = "calendar-6";

    @Override
    public Completable rxStart() {
        try{
            EventLogs eventLogs = EventLogs.getInstance();
//        eventLogs.testSearch().forEach(jsonObject -> log.info(jsonObject.encodePrettily()));
            List<JsonObject> events = eventLogs.fetchAll(RDF_REPO_ID);
            //events.forEach(jsonObject -> log.info(jsonObject.encodePrettily()));
            log.info("{} events", events.size());
            saveEvents(events);

            Neo4JParser neo4j = new Neo4JParser("bolt://localhost", "neo4j","neo4j2");

            XPathProbabilityParser parser = new XPathProbabilityParser();
            XPathProbabilities xpp = parser.parse(events);
            Set<XpathValue> xvs = xpp.getXpathValues();


            //neo4j.materializeXpath("/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div[1]/div/div/div/div[2]/a/i");
            xpp.watchedXpaths().forEach(xpath->neo4j.materializeXpath(xpath));

//            xvs.forEach(xv->neo4j.createNode(xv.xpath(), xv.value()));
            xvs.forEach(xv->{
                Map<String, Map<String,Integer>> info = xpp.compute(xv.xpath(), xv.value());
                info.forEach((xpath, map)->{
                    map.forEach((value, count)->{
                        if (value == "all"){
                            return;
                        }
                        int total = map.get("all");
                        neo4j.linkNodes(xv.xpath(), xv.value(), xpath, value, (double)count/(double) total, count, total);
                    });
                });
            });

            log.info("{}", xpp.toString());
            log.info("xvs: {}", xvs.size());

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return super.rxStart();
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
