import ca.ualberta.odobot.semanticflow.ElementTraversal;

import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ElementTraversalTest {

    private static final Logger log = LoggerFactory.getLogger(ElementTraversalTest.class);
    private static JsonObject eventJson;
    private static Document document;



    class Visitor implements NodeVisitor{

        List<Element> elementList = new ArrayList<>();

        @Override
        public void head(Node node, int i) {
            if(node instanceof Element){
                Element e = (Element)node;
                log.info("Entering {}", e.tagName());
                elementList.add(e);
            }

        }

        @Override
        public void tail(Node node, int depth) {
            if(node instanceof Element){
                Element e = (Element) node;
                log.info("Exiting {}", e.tagName());
            }
        }

        public List<Element> getElementList(){
            return elementList;
        }
    };

    @BeforeAll
    static void setup() throws IOException{
        FileInputStream fis = new FileInputStream("src/test/resources/interactionEventExample.json");
        eventJson = new JsonObject(IOUtils.toString(fis, "UTF-8"));

        JsonObject domInfo = new JsonObject(eventJson.getString("eventDetails_domSnapshot"));
        String htmlData = domInfo.getString("outerHTML");
        document = Jsoup.parse(htmlData);
    }

    @Test
    void documentBaseUri(){
        log.info("base uri is: {}",document.baseUri());
        log.info("{}", document.baseUri().isEmpty());
    }

    @Test
    void visitNodes(){
        NodeVisitor visitor = new Visitor();

        NodeTraversor.traverse(visitor, document.root());
    }

    @Test
    void stepVisitNodes(){
        ElementTraversal traversal = new ElementTraversal(new ElementTraversal.SimpleVisitor(), document.root());

        while (traversal.hasNext()){
            traversal.traverse();
        }
    }

    /**
     * Test the validity of the stepwise element traversal code by comparing it
     * to the JSOUP non-stepwise results.
     */
    @Test
    void stepVisitValidity(){
        //Create Jsoup visitor and run traversal
        Visitor visitor = new Visitor();
        NodeTraversor.traverse(visitor, document.root());

        //Create element visitor and run stepwise element traversal
        ElementTraversal.SimpleVisitor eVisitor = new ElementTraversal.SimpleVisitor();
        ElementTraversal traversal = new ElementTraversal(eVisitor, document.root());
        while (traversal.hasNext()){
            traversal.traverse();
        }

        //Extract results from visitors
        List<Element> jsoup = visitor.getElementList();
        List<Element> eTraversal = eVisitor.getElementList();

        //Same number of results.
        assertEquals(jsoup.size(), eTraversal.size());

        //Same elements and order.
        for(int i = 0; i < jsoup.size(); i++){
            Element jsoupElement = jsoup.get(i);
            Element traversalElement = eTraversal.get(i);
            assertEquals(jsoupElement, traversalElement);
        }

        log.info("jsoup: {}", jsoup.stream().map(e->e.tagName() + " ").collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString());
        log.info("etraversal: {}", eTraversal.stream().map(e->e.tagName() + " ").collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString());
    }

}
