package ca.ualberta.odobot;


import ca.ualberta.odobot.common.ConfigurableVerticle;
import ca.ualberta.odobot.explorer.ExplorerVerticle;
import ca.ualberta.odobot.guidance.GuidanceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.snippets.Extractor;
import ca.ualberta.odobot.tpg.TPGVerticle;
import ca.ualberta.odobot.web.OdoSightSupport;
import ca.ualberta.odobot.web.TimelineWebApp;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends ConfigurableVerticle {
    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);


    @Override
    public String configFilePath() {
        return "config/main.yaml";
    }

    @Override
    public String serviceName() {
        return "Main Verticle";
    }

    @Override
    public Completable onStart() {

        log.info("MainVerticle starting!");

        if(_config.getBoolean("LogPreProcessor")){
            LogPreprocessor logPreprocessor = new LogPreprocessor();
            vertx.deployVerticle(logPreprocessor);
        }

        if(_config.getBoolean("TimelineWebApp")){
            TimelineWebApp timelineWebApp = new TimelineWebApp();
            vertx.deployVerticle(timelineWebApp);
        }

        if(_config.getBoolean("OdoSightSupport")){
            OdoSightSupport odoSightSupport = new OdoSightSupport();
            vertx.deployVerticle(odoSightSupport);
        }

        if(_config.getBoolean("TPG")){
            TPGVerticle tpgVerticle = new TPGVerticle();
            vertx.deployVerticle(tpgVerticle);
        }

        if(_config.getBoolean("Explorer")){
            ExplorerVerticle explorerVerticle = new ExplorerVerticle();
            vertx.deployVerticle(explorerVerticle);
        }

        if(_config.getBoolean("SnippetExtractor")){
            Extractor extractor = new Extractor();
            vertx.deployVerticle(extractor);
        }

        if(_config.getBoolean("Guidance")){
            GuidanceVerticle guidanceVerticle = new GuidanceVerticle();
            vertx.deployVerticle(guidanceVerticle);
        }



        return Completable.complete();
    }
}
