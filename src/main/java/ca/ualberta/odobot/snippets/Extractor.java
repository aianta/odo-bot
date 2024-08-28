package ca.ualberta.odobot.snippets;


import ca.ualberta.odobot.common.HttpServiceVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Extractor extends HttpServiceVerticle {


    private static final Logger log = LoggerFactory.getLogger(Extractor.class);


    @Override
    public String serviceName() {
        return "Snippet Extractor";
    }

    @Override
    public String configFilePath() {
        return "config/snippet-extraction.yaml";
    }

}
