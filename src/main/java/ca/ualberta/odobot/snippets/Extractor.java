package ca.ualberta.odobot.snippets;


import ca.ualberta.odobot.common.HttpServiceVerticle;
import io.reactivex.rxjava3.core.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Extractor extends HttpServiceVerticle {


    private static final Logger log = LoggerFactory.getLogger(Extractor.class);


    public Completable onStart(){
        super.onStart();

        return Completable.complete();
    }

    @Override
    public String serviceName() {
        return "Snippet Extractor";
    }

    @Override
    public String configFilePath() {
        return "config/snippet-extraction.yaml";
    }

}
