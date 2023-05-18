package ca.ualberta.odobot.logpreprocessor;

import io.vertx.rxjava3.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * @author Alexandru Ianta
 * Manages persistence logic for log preprocessing pipelines.
 *
 * Key Idea:
 * * Create a map of keys inside the {@link RoutingContext} whose values should be persisted. We'll call these 'persistence targets'.
 * * Whenever the handler is invoked, check to see if the request has been marked transient. Transient requests do not persist their artifacts.
 * * If the request is <b>not</b> transient, go through the keys in the {@link RoutingContext} and invoke all corresponding persistence logic.
 * * {@link PersistenceType}s control whether the persistence layer will invoke persistence logic only once per request, or everytime the persistence handler is called.
 */
public class PipelinePersistenceLayer {
    private static final Logger log = LoggerFactory.getLogger(PipelinePersistenceLayer.class);

    public enum PersistenceType {
        ONCE, ALWAYS
    }

    Map<String, Consumer> persistenceTargets = new HashMap<>();
    Map<String, PersistenceType> typeMap = new HashMap<>();
    Map<UUID, Set<String>> invocations = new HashMap<>();

    /**
     * Register persistence logic for a {@link RoutingContext} key.
     * @param target the key
     * @param consumer the persistence logic to execute.
     */
    public  <T> void registerPersistence(String target, Consumer<T> consumer, PersistenceType type){
        persistenceTargets.put(target, consumer);
        typeMap.put(target, type);
    }

    public void persistenceHandler(RoutingContext rc){
        /**
         * If this routing context has no persistence id, create one now and put it in the context.
         * We'll use this to figure out which persistence target's we've already executed logic for
         * so we can appropriately fire {@link PersistenceType.ONCE} or {@link PersistenceType.ALWAYS}.
         */
        if(rc.get("persistenceId") == null){
            UUID newId = UUID.randomUUID();
            rc.put("persistenceId", newId );
        };

        UUID persistenceId = rc.get("persistenceId");

        // Only invoke persistence logic if the request is not transient.
        if(!(Boolean)rc.get("isTransient")){
            persistenceTargets.keySet().forEach(persistenceTarget->{
                //Only execute persistence logic IFF
                if(rc.data().containsKey(persistenceTarget) && //The routing context contains the persistence target
                        //AND either
                        (typeMap.get(persistenceTarget) == PersistenceType.ALWAYS || //the persistence target is set to ALWAYS persist OR
                        (typeMap.get(persistenceTarget) == PersistenceType.ONCE &&  //the persistence target is set to persist ONCE AND
                                !invocations.getOrDefault(persistenceId, new HashSet<String>()).contains(persistenceTarget))) //it does not appear in the set of persistence targets we've persisted for this request.
                ){
                    //Execute persistence logic
                    persistenceTargets.get(persistenceTarget).accept(rc.get(persistenceTarget));
                    //Record the invocations of the persistence logic for this persistence target
                    Set<String> history = this.invocations.getOrDefault(persistenceId,new HashSet<>());
                    history.add(persistenceTarget);
                    this.invocations.put(persistenceId, history);

                }
            });
        }

        rc.next();
    }

}
