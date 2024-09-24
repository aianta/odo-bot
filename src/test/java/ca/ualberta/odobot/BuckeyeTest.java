package ca.ualberta.odobot;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BuckeyeTest {

    private static final Logger log = LoggerFactory.getLogger(BuckeyeTest.class);

    @Test
    void test() throws IOException {

        String rawHTML = new String(Files.readAllBytes(Path.of("state.html")));

        rawHTML = rawHTML.replaceAll("iframe", "div");

        String buckeye = "//*[@data_pw_testid_buckeye]";
        String expected = "//*[@data_pw_testid_buckeye='d1772f3d-086d-4f30-b37f-eed1de2786aa']";

        Document document = Jsoup.parse(rawHTML);

        log.info("does raw HTML include expected buckeye value? {}", rawHTML.contains("d1772f3d-086d-4f30-b37f-eed1de2786aa"));

        Elements withExpected = document.selectXpath(expected);

        Elements withBuckeye = document.selectXpath(buckeye);

        log.info("complete");


    }

}
