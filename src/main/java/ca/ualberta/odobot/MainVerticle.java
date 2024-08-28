package ca.ualberta.odobot;


import ca.ualberta.odobot.explorer.ExplorerVerticle;
import ca.ualberta.odobot.guidance.GuidanceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.tpg.TPGVerticle;
import ca.ualberta.odobot.web.OdoSightSupport;
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

        OdoSightSupport odoSightSupport = new OdoSightSupport();
        TPGVerticle tpgVerticle = new TPGVerticle();
        TimelineWebApp timelineWebApp = TimelineWebApp.getInstance();
        LogPreprocessor logPreprocessor = new LogPreprocessor();
        ExplorerVerticle explorerVerticle = new ExplorerVerticle();
        GuidanceVerticle guidanceVerticle = new GuidanceVerticle();

        vertx.deployVerticle(logPreprocessor);
        vertx.deployVerticle(timelineWebApp);
        vertx.deployVerticle(odoSightSupport);
        //vertx.deployVerticle(tpgVerticle);
        vertx.deployVerticle(explorerVerticle);
        vertx.deployVerticle(guidanceVerticle);

        return super.rxStart();
    }
}
