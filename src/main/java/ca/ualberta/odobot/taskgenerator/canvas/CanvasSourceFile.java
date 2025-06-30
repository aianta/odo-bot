package ca.ualberta.odobot.taskgenerator.canvas;

public class CanvasSourceFile {

    String url;
    String localPath;
    String title;

    String body;

    public String getLocalPath() {
        return localPath;
    }

    public CanvasSourceFile setLocalPath(String localPath) {
        this.localPath = localPath;
        return this;
    }

    public String getUrl() {
        return url;
    }

    public String getFileSafeTitle(){
        return getTitle().replaceAll(" ", "-").toLowerCase().replaceAll("[^a-z0-9\\-]", "");
    }

    public CanvasSourceFile setUrl(String url) {
        this.url = url;
        return this;
    }

    public String getTitle() {
        return title;
    }

    public CanvasSourceFile setTitle(String title) {
        this.title = title;
        return this;
    }

    public String getBody() {
        return body;
    }

    public CanvasSourceFile setBody(String body) {
        this.body = body;
        return this;
    }
}
