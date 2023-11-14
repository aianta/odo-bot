package ca.ualberta.odobot.domsequencing.impl;

import ca.ualberta.odobot.domsequencing.DOMSequence;
import ca.ualberta.odobot.domsequencing.DOMSequencingService;
import ca.ualberta.odobot.domsequencing.DOMVisitor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DOMSequencingServiceImpl implements DOMSequencingService {

    List<DOMSequence> database = new ArrayList<>();

    @Override
    public Future<JsonObject> process(String html) {

        Document doc = Jsoup.parse(html);
        DOMVisitor visitor = new DOMVisitor();
        doc.traverse(visitor);

        DOMSequence sequence = visitor.getSequence();

        database.add(sequence);

        return Future.succeededFuture(sequence.toJson());
    }

    public Future<List<JsonObject>> getSequences(){

        List<JsonObject> result = database.stream()
                .map(sequence->sequence.toJson())
                .collect(Collectors.toList());

        return Future.succeededFuture(result);
    }

    public Future<Void> clearSequences(){
        database.clear();
        return Future.succeededFuture();
    }
}
