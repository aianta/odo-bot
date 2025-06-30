package ca.ualberta.odobot.taskgeneration;

import ca.ualberta.odobot.taskgenerator.canvas.CanvasSourceFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ProcessingCanvasUserManualPage {

    private static final Logger log = LoggerFactory.getLogger(ProcessingCanvasUserManualPage.class);
    private static final String sourcePath = "src/test/resources/sample_canvas_user_manual_task.html";
    private static final String canvasTableOfContentsPage = "https://community.canvaslms.com/t5/Student-Guide/tkb-p/student";
    private static final String canvasRootUrl = "https://community.canvaslms.com";
    private static final String canvasManualRootPath = "canvas-user-manual";


    @Test
    public void extraction() throws IOException {

        String html = new String(Files.readAllBytes(Path.of(sourcePath)));
        Document document = Jsoup.parse(html);

        extractText(document);



    }

    @Test
    public void pullCanvasData() throws IOException {

        //Setup Canvas User Manual root path
        Path canvasRootPath = Path.of(canvasManualRootPath);
        Path canvasToCPath = Path.of(canvasRootPath.toAbsolutePath() + "/toc.html");
        if(!Files.exists(canvasRootPath)){
            Files.createDirectory(canvasRootPath);
        }

        //Check if ToC has already been downloaded
        Document tableOfContents = null;
        if(Files.exists(canvasToCPath)){
            String tocHtml = new String(Files.readAllBytes(canvasToCPath));
            tableOfContents = Jsoup.parse(tocHtml);
        }else{
            tableOfContents = Jsoup.parse(new URL(canvasTableOfContentsPage), 10000);
        }

        //Save table of contents
        if (!Files.exists(canvasToCPath)){
            Files.writeString(canvasToCPath, tableOfContents.outerHtml());
        }

        List<CanvasSourceFile> userManual = getManualPages(tableOfContents);

        try{
            for(CanvasSourceFile page: userManual){
                page.setLocalPath(Path.of(canvasRootPath.toAbsolutePath() + "/" + page.getFileSafeTitle() + ".html").toString());
                saveHTML(page.getUrl(), Path.of(page.getLocalPath()));
            }
        }catch (Exception e){
            log.error(e.getMessage(), e);
        }





    }

    public static void saveHTML(String url, Path path) throws IOException {

        if(Files.exists(path)){
            return; //Skip pages we already have.
        }

        Document doc = Jsoup.parse(new URL(url), 10000);
        Files.writeString(path, doc.outerHtml());
        log.info("Saved: {}", path.toString());

    }

    public static List<CanvasSourceFile> getManualPages(Document tableOfContents){
        List<CanvasSourceFile> results = new ArrayList<>();

        Iterator<Element> it = tableOfContents.select(".toc-main>section>ul>li>a").iterator();
        while (it.hasNext()){
            Element anchorTag = it.next();
            CanvasSourceFile page = new CanvasSourceFile();
            page.setUrl(canvasRootUrl + anchorTag.attribute("href").getValue());
            page.setTitle(anchorTag.text());

            results.add(page);
        }

        return results;
    }

    public static void extractText(Document document){

        String title = document.select(".lia-message-subject").first().text();

        String body = document.select(".lia-message-body-content").first().text();

        log.info("title:\n{}", title);
        log.info("body:\n{}", body);

    }


}
