package ca.ualberta.odobot;

import io.vertx.core.VertxOptions;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class Launcher extends io.vertx.core.Launcher {

    @Override
    public void beforeStartingVertx(VertxOptions options) {
        /**
         * Set up logging using SLF4J
         */
        System.setProperty("vertx.logger-delegate-factory-class-name","io.vertx.core.logging.SLF4JLogDelegateFactory");


//        options.setBlockedThreadCheckInterval(10);
//        options.setBlockedThreadCheckIntervalUnit(TimeUnit.MINUTES);
        options.setWorkerPoolSize(4)
                .setMaxEventLoopExecuteTime(10)
                .setMaxEventLoopExecuteTimeUnit(TimeUnit.MINUTES)
                .setMaxWorkerExecuteTime(10)
                .setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES)
                .setInternalBlockingPoolSize(4)
                .setBlockedThreadCheckInterval(10)
                .setBlockedThreadCheckIntervalUnit(TimeUnit.MINUTES)
                .setWarningExceptionTime(10)
                .setWarningExceptionTimeUnit(TimeUnit.MINUTES)
        ;

        //super.beforeStartingVertx(options);
    }



}
