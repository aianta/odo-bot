package ca.ualberta.odobot;

import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringCleaning {

    private static final Logger log = LoggerFactory.getLogger(StringCleaning.class);


    @Test
    public void trimmingXpaths(){
        var sample = "html/btn[3]";

        if(sample.lastIndexOf("/btn") != -1){
            var trimmed = sample.substring(0, sample.lastIndexOf("/btn") + 4);
            var remainder = sample.substring(trimmed.length()).split("/")[0];
            log.info("trimmed: {} remainder: {}", trimmed, remainder);
        }

    }

    @Test
    public void why(){

        String sample = "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\" \"http://www.w3.org/TR/REC-html40/loose.dtd\">\n<html backend_node_id=\"191\" bounding_box_rect=\"0,0,1280,1080\" class=\"single-event\">\n  ";

        sample = sample.replaceAll("\n", "");

        System.out.println(sample);

    }

    @Test
    public void jsonArray(){

        String input = """
                [
                    ["Email Address", "ianta@ualberta.ca"],
                    ["Password", "01134hello"],
                    ["Course Name", "SPA101 Beginning Spanish I (36086)"],
                    ["Quiz Title", null],
                    ["Assignment Name", null],
                    ["Page Title", null],
                    ["Module Name", null],
                    ["Online Submission Type - Text Entry", null]
                ]
                """;

        JsonArray array = new JsonArray(input);
        log.info("{}", array.encodePrettily());
    }
}
