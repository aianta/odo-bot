package ca.ualberta.odobot;


import ca.ualberta.odobot.semanticflow.SemanticFlowParser;
import ca.ualberta.odobot.web.TimelineWebApp;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public Completable rxStart() {
        log.info("MainVerticle starting!");

        SemanticFlowParser svp = new SemanticFlowParser();
        TimelineWebApp timelineWebApp = TimelineWebApp.getInstance();

        vertx.deployVerticle(svp);
        vertx.deployVerticle(timelineWebApp);

        return super.rxStart();
    }
}
