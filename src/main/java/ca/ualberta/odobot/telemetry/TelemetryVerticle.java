package ca.ualberta.odobot.telemetry;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.serviceproxy.ServiceBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ca.ualberta.odobot.logpreprocessor.Constants.TELEMETRY_SERVICE_ADDRESS;

public class TelemetryVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryVerticle.class);

    public static TelemetryService telemetryService;

    @Override
    public String serviceName() {
        return "TelemetryService";
    }

    @Override
    public String configFilePath() {
        return "config/telemetry.yaml";
    }


    public Completable onStart(){
        super.onStart();

        telemetryService = TelemetryService.create(vertx.getDelegate(), _config);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(TELEMETRY_SERVICE_ADDRESS)
                .register(TelemetryService.class, telemetryService);


        return Completable.complete();
    }


}
