package ca.ualberta.odobot;

import io.vertx.core.VertxOptions;

import java.util.concurrent.TimeUnit;

public class Launcher extends io.vertx.core.Launcher {

    @Override
    public void beforeStartingVertx(VertxOptions options) {
//        options.setBlockedThreadCheckInterval(10);
//        options.setBlockedThreadCheckIntervalUnit(TimeUnit.MINUTES);
        options.setWorkerPoolSize(14)
                .setMaxEventLoopExecuteTime(10)
                .setMaxEventLoopExecuteTimeUnit(TimeUnit.MINUTES)
                .setMaxWorkerExecuteTime(10)
                .setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES)
                .setInternalBlockingPoolSize(14)
                .setBlockedThreadCheckInterval(10)
                .setBlockedThreadCheckIntervalUnit(TimeUnit.MINUTES)
                .setWarningExceptionTime(10)
                .setWarningExceptionTimeUnit(TimeUnit.MINUTES)
        ;

        super.beforeStartingVertx(options);
    }



}
