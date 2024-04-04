package ca.ualberta.odobot;

import ca.ualberta.odobot.domsequencing.DOMSegment;
import ca.ualberta.odobot.domsequencing.DOMSequence;
import ca.ualberta.odobot.domsequencing.DOMVisitor;
import ca.ualberta.odobot.domsequencing.impl.SmithWatermanV2;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.Supplier;

public class SmithWatermanV2Test {

    private static final Logger log = LoggerFactory.getLogger(SmithWatermanV2Test.class);

    final String htmlSource1 = "page_save_sample.html";
    final String htmlSource2 = "page_save_sample_2.html";

    final String htmlSource4 = "update_question_sample_2.html";

    final String htmlSource3 = "update_question_sample.html";

    String [] tagPool = {"div", "a", "html", "body", "link", "meta", "script", "span", "p","td", "tr","th","table"};
    String [] classPool = {"container", "header", "login", "no-border", "btn"};

    static Random random = new Random();


    @Test
    void test(){

        DOMSequence sequence1 = sequenceFromHTML(loadHtml(htmlSource1), -1);
        DOMSequence sequence2 = sequenceFromHTML(loadHtml(htmlSource2), -1);

        SmithWatermanV2 sw = new SmithWatermanV2(-1);

        sw.align(sequence1, sequence2 );

    }

    String loadHtml(String source){
        try {
           String html = new String(Files.readAllBytes(Path.of(source)));

            return html;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


    }

    DOMSequence sequenceFromHTML(String html, int maxLength){
        Document doc = Jsoup.parse(html);
        DOMVisitor visitor = new DOMVisitor();
        doc.traverse(visitor);

        DOMSequence result = new DOMSequence();

        if(maxLength == -1){
            return visitor.getSequence();
        }

        visitor.getSequence().stream().limit(maxLength).forEach(result::add);

        return result;
    }


    DOMSequence makeTestSequence(int length){
        DOMSequence result = new DOMSequence();


        Supplier<DOMSegment> supplier = ()->{
            return new DOMSegment(tagPool[random.nextInt(tagPool.length)], classPool[random.nextInt(classPool.length)], "/");
        };

        int i = 0;
        while (i < length){
            result.add(supplier.get());
            i++;
        }

        return result;
    }

}
