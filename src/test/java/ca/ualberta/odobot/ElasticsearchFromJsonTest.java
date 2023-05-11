package ca.ualberta.odobot;

import co.elastic.clients.elasticsearch._types.SortOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.StringReader;

public class ElasticsearchFromJsonTest {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchFromJsonTest.class);
    @Test
    void parseSortOptions(){

        String test = """
                {
                  "sort" : [
                    { "term" : "asc"}
                  ]
                }
                """;

        String test2 = """
                {
                    "term":"asc"
                }
                """;

        SortOptions so = SortOptions.of(b->b.withJson(new StringReader(test2)));

    }

}
