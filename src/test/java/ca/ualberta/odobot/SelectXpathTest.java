package ca.ualberta.odobot;


import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SelectXpathTest {

    @Test
    public void test() throws IOException {

        String html = new String(Files.readAllBytes(Path.of("src/test/resources/debug.html.txt")));
        Document document = Jsoup.parse(html);
        int foundElements = document.selectXpath(xpath).size();
        System.out.println("Found %s elements".formatted(foundElements));

        assert foundElements > 0;

    }

    private static final String xpath = "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[1]/form/div[1]/div[5]/fieldset[1]/div[2]/div/div[1]/label[1]/input[2]";

}
