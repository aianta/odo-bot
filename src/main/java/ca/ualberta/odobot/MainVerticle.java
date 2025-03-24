package ca.ualberta.odobot;


import ca.ualberta.odobot.common.ConfigurableVerticle;
import ca.ualberta.odobot.dataentry2label.DataEntry2LabelVerticle;
import ca.ualberta.odobot.explorer.ExplorerVerticle;
import ca.ualberta.odobot.guidance.GuidanceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.mind2web.Mind2WebService;
import ca.ualberta.odobot.snippet2xml.Snippet2XMLVerticle;
import ca.ualberta.odobot.snippets.Extractor;
import ca.ualberta.odobot.taskplanner.TaskPlannerVerticle;
import ca.ualberta.odobot.tpg.TPGVerticle;
import ca.ualberta.odobot.web.OdoSightSupport;
import ca.ualberta.odobot.web.TimelineWebApp;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.DeploymentOptions;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

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

        if(_config.getBoolean("Mind2Web")){
            Mind2WebService mind2WebService = new Mind2WebService();
            vertx.deployVerticle(mind2WebService, new DeploymentOptions()
                    .setWorkerPoolName("mind2web-pool")
                    .setWorkerPoolSize(8)
                    .setMaxWorkerExecuteTime(1)
                    .setMaxWorkerExecuteTimeUnit(TimeUnit.HOURS) //TODO: make this configrable?
            );
        }

        if(_config.getBoolean("Snippet2XML")){
            Snippet2XMLVerticle snippet2XMLVerticle = new Snippet2XMLVerticle();
            vertx.deployVerticle(snippet2XMLVerticle, new DeploymentOptions()
                    .setWorkerPoolName("snippet2xml-pool")
                    .setWorkerPoolSize(8)
                    .setMaxWorkerExecuteTime(1)
                    .setMaxWorkerExecuteTimeUnit(TimeUnit.HOURS) //TODO: make this configrable?
            );
        }

        if(_config.getBoolean("DataEntry2Label")){
            DataEntry2LabelVerticle dataEntry2LabelVerticle = new DataEntry2LabelVerticle();
            vertx.deployVerticle(dataEntry2LabelVerticle);
        }


        if(_config.getBoolean("TaskPlanner")){
            TaskPlannerVerticle taskPlannerVerticle = new TaskPlannerVerticle();
            vertx.deployVerticle(taskPlannerVerticle);
        }

        return Completable.complete();
    }
}
