package ca.ualberta.odobot;

import org.junit.jupiter.api.Test;

public class StringCleaning {

    @Test
    public void why(){

        String sample = "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\" \"http://www.w3.org/TR/REC-html40/loose.dtd\">\n<html backend_node_id=\"191\" bounding_box_rect=\"0,0,1280,1080\" class=\"single-event\">\n  ";

        sample = sample.replaceAll("\n", "");

        System.out.println(sample);

    }
}
