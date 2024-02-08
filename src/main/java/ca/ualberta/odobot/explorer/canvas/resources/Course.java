package ca.ualberta.odobot.explorer.canvas.resources;

import java.net.MalformedURLException;
import java.net.URL;

public class Course {

    String name;

    String coursePageUrl;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCoursePageUrl() {
        return coursePageUrl;
    }

    public URL getCoursePageUrlAsURL(){
        try {
            return new URL(coursePageUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setCoursePageUrl(String coursePageUrl) {
        this.coursePageUrl = coursePageUrl;
    }
}
