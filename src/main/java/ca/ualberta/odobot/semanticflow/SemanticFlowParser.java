package ca.ualberta.odobot.semanticflow;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.apache.http.HttpHost;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.elasticsearch.client.RestClient;
import org.locationtech.jts.awt.PointShapeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

public class SemanticFlowParser extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SemanticFlowParser.class);
    private static final String RDF_REPO_ID = "calendar-2";

    @Override
    public Completable rxStart() {

        EventLogs eventLogs = EventLogs.getInstance();
//        eventLogs.testSearch().forEach(jsonObject -> log.info(jsonObject.encodePrettily()));
        List<JsonObject> events = eventLogs.fetchAll(RDF_REPO_ID);
        events.forEach(jsonObject -> log.info(jsonObject.encodePrettily()));
        log.info("{} events", events.size());

        XPathProbabilityParser parser = new XPathProbabilityParser();
        parser.parse(events);


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
}
