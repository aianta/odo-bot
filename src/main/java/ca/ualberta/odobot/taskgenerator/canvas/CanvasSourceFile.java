package ca.ualberta.odobot.taskgenerator.canvas;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CanvasSourceFile {

    private static final Logger log = LoggerFactory.getLogger(CanvasSourceFile.class);

    String url;
    String localPath;
    String title;

    String body;


    public static CanvasSourceFile loadFromFile(String path){
        log.info("Loading CanvasSourceFile from {}", path);
        try{

            String data = new String(Files.readAllBytes(Path.of(path)));
            Document document = Jsoup.parse(data);

            CanvasSourceFile page = new CanvasSourceFile();
            page.setLocalPath(path);
            page.setTitle(document.select(".lia-message-subject").first().text());
            page.setBody(document.select(".lia-message-body-content").first().text());

            return page;
        }catch (IOException e){
            log.error(e.getMessage(), e);
        }

        log.error("Error while loading CanvasSourceFile from {}", path);
        return null;
    }

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
