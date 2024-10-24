package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.jsoup.nodes.Document;

import java.util.List;
import java.util.Set;

public class DynamicXpathMiningTask implements Runnable{

    private String id;

    private String website;

    private List<String> xpaths;

    private Document document;

    private Promise<Set<DynamicXPath>> promise;



    public DynamicXpathMiningTask( String id, String website, Document document, List<String> xpaths){
        this.id = id;
        this.website = website;
        this.xpaths = xpaths;
        this.document = document;
        this.promise = Promise.promise();
    }

    public Future<Set<DynamicXPath>> getFuture(){
        return promise.future();
    }

    @Override
    public void run() {

        Set<DynamicXPath> minedDynamicXpaths = DynamicXpathMiner.mine(document, xpaths);

        promise.complete(minedDynamicXpaths);

        minedDynamicXpaths.forEach(dynamicXpath->Mind2WebService.sqliteService.saveDynamicXpathForWebsite(dynamicXpath.toJson(), dynamicXpath.toString(), id, website));


    }
}
