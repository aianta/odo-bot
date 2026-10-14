package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The golden files written by scripts/webarena-parity/gen_golden.py from WebArena's own evaluator code, and a way to
 * report every mismatch at once.
 */
final class Golden {

    private Golden(){}

    static JsonArray load(String name){
        String resource = "/webarena/golden/" + name + ".json.gz";
        try(InputStream raw = Golden.class.getResourceAsStream(resource)){
            if(raw == null){
                throw new IllegalStateException(resource + " is missing; run scripts/webarena-parity/gen_golden.py");
            }
            try(InputStream in = new GZIPInputStream(raw)){
                return (JsonArray) Json.decodeValue(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }catch (IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Collects mismatches and fails with the first few, so one run shows how widespread a difference is.
     */
    static final class Mismatches {
        private final String what;
        private final List<String> found = new ArrayList<>();
        private int checked = 0;

        Mismatches(String what){
            this.what = what;
        }

        void check(boolean ok, String description){
            checked++;
            if(!ok){
                found.add(description);
            }
        }

        void assertNone(){
            if(checked == 0){
                fail("No %s cases were checked".formatted(what));
            }
            if(!found.isEmpty()){
                fail("%d of %d %s cases differ from the original. First ones:\n%s".formatted(found.size(), checked, what,
                        String.join("\n", found.subList(0, Math.min(25, found.size())))));
            }
        }
    }
}
