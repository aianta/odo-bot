package ca.ualberta.odobot;

import ca.ualberta.odobot.explorer.canvas.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

public class LoadIMSCCFileTest {

    private static final Logger log = LoggerFactory.getLogger(LoadIMSCCFileTest.class);

    @Test
    void load(){

        ResourceManager.loadCourse("english_9_canvas_course.imscc");

    }

}
