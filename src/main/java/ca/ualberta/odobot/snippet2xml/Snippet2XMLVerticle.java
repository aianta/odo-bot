package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Snippet2XMLVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(Snippet2XMLVerticle.class);

    @Override
    public String serviceName() {
        return "Snippet2XML Service";
    }

    @Override
    public String configFilePath() {
        return "config/snippet2xml.yaml";
    }





}
