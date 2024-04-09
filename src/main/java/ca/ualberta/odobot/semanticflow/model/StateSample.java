package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.sqlclient.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class StateSample {

    private static final Logger log = LoggerFactory.getLogger(StateSample.class);

    public List<String> domTree;

    public double [] hashedDOMTree;

    public UUID id = UUID.randomUUID();

    public String baseURI;

    public JsonObject extras = new JsonObject();

    public String datasetName;

    public String source;

    public String sourceSymbol;

    public String domHTML;

    public static StateSample fromRow(Row r){
        StateSample sample = new StateSample();
        sample.baseURI = r.getString("base_uri");
        sample.datasetName = r.getString("dataset_name");
        sample.id = UUID.fromString(r.getString("id"));
        sample.source = r.getString("source");
        sample.extras = new JsonObject(r.getString("extras"));
        sample.domTree = new JsonArray(r.getString("dom_tree")).stream().map(o->(String)o).collect(Collectors.toList());
        sample.hashedDOMTree = new JsonArray(r.getString("hashed_dom_tree")).stream().mapToDouble(o->(double)o).toArray();
        sample.domHTML = r.getString("dom_html");
        sample.sourceSymbol = r.getString("source_symbol");

        return sample;
    }

    public static StateSample fromJson(JsonObject json){
        StateSample sample = new StateSample();
        sample.baseURI = json.getString("baseURI");
        sample.datasetName = json.getString("datasetName");
        sample.id = UUID.fromString(json.getString("id"));
        sample.source = json.getString("source");
        sample.extras = json.getJsonObject("extras");
        sample.domTree = json.getJsonArray("domTree").stream().map(o->(String)o).collect(Collectors.toList());
        sample.hashedDOMTree = json.getJsonArray("hashedDomTree").stream().mapToDouble(o->(double) o).toArray();
        sample.domHTML = json.getString("domHTML");
        sample.sourceSymbol = json.getString("sourceSymbol");

        return sample;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("baseURI", baseURI)
                .put("datasetName", datasetName)
                .put("id", id.toString())
                .put("source", source)
                .put("extras", extras)
                .put("domTree", domTree.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("hashedDomTree", Arrays.stream(hashedDOMTree).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("domHTML", domHTML)
                .put("sourceSymbol", sourceSymbol)
                ;


        return result;
    }

    public String normalizedBaseUri(){
        try{
            URL url = new URL(baseURI);

            //TODO - page permalink normalization is being used here.
            return url.getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");

        }catch (MalformedURLException e){
            log.error("Malformed URL: " + baseURI);
        }

        return null;
    }



}
