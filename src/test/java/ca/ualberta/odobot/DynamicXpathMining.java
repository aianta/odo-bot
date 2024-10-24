package ca.ualberta.odobot;

import ca.ualberta.odobot.mind2web.DynamicXpathMiner;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.List;
import java.util.Set;

public class DynamicXpathMining {

    private static final Logger log = LoggerFactory.getLogger(DynamicXpathMining.class);


    @Test
    public void test(){

        Document doc = Jsoup.parse(sampleDocument1);
        Document doc2 = Jsoup.parse(sampleDocument2);
        Document doc3 = Jsoup.parse(sampleDocument3);
        Document doc4 = Jsoup.parse(sampleDocument4);
        Document doc5 = Jsoup.parse(sampleDocument5);
        Document doc6 = Jsoup.parse(sampleDocument6);
        Document doc7 = Jsoup.parse(sampleDocument7);

        Set<DynamicXPath> dynamicXpaths = DynamicXpathMiner.mine(doc, List.of(sampleXpath1));

        log.info("Dynamic Xpaths 1: ");
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));

        log.info("Dynamic Xpaths 2:");
        dynamicXpaths = DynamicXpathMiner.mine(doc2, List.of(sampleXpath1));
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));

        log.info("Dynamic Xpaths 3:");
        dynamicXpaths = DynamicXpathMiner.mine(doc3, List.of(sampleXpath1));
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));

        log.info("Dynamic Xpaths 4:");
        dynamicXpaths = DynamicXpathMiner.mine(doc4, List.of(sampleXpath1));
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));

        log.info("Dynamic Xpaths 5:");
        dynamicXpaths = DynamicXpathMiner.mine(doc5, List.of(sampleXpath1));
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));

        log.info("Dynamic Xpaths 6:");
        dynamicXpaths = DynamicXpathMiner.mine(doc6, List.of(sampleXpath1));
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));

        log.info("Dynamic Xpaths 7:");
        dynamicXpaths = DynamicXpathMiner.mine(doc7, List.of(sampleXpath3));
        dynamicXpaths.forEach(x->log.info("{}", x.toJson().encodePrettily()));
    }

    String sampleDocument1 = """
            <html>
                <body>
                    <div>
                        <div>
                            <span>Element</span>
                        </div>
                        <div>
                            <span>Element</span>
                        </div>
                    </div>
                </body>
            </html>
            """;


    String sampleDocument2 = """
            <html>
                <body>
                    <div>
                        <div>
                            <span>Element</span>
                        </div>
                        <div>
                            <span>Element</span>
                            <btn>Login</btn>
                        </div>
                        <div>
                            <span>Element</span>
                            <span>Element</span>
                        </div>
                    </div>
                </body>
            </html>
            """;
    String sampleDocument3 = """
            <html>
                <body>
                    <div>
                        <div>
                            <span>Element</span>
                        </div>
                        <div>
                            <span>Element</span>
                        </div>
                        <div>
                            <span>Element</span>
                            <span>Element</span>
                        </div>
                    </div>
                </body>
            </html>
            """;

    String sampleDocument4 = """
            <html>
                <body>
                    <div>
                        <div>
                            <span>Element</span>
                        </div>
                        <div>
                            <span>Element<span>SubElement</span></span>
                        </div>
                        <div>
                            <span>Element</span>
                            <span>Element</span>
                        </div>
                    </div>
                </body>
            </html>
            """;

    String sampleDocument5 = """
            <html>
                <body>
                    <div>
                        <div>
                            <label>Submit button:</label>
                            <btn><span>Element</span></btn>
                        </div>
                        <div>
                            <label>Submit button:</label>
                            <btn><span>Element</span></btn>
                        </div>
                    </div>
                </body>
            </html>
            """;

    String sampleDocument6 = """
            <html>
                <body>
                    <div>
                        <div>
                            <span>Element</span>
                        </div>
                        <div>
                            <span>Element</span>
                             <span>Element</span>
                        </div>
                    </div>
                </body>
            </html>
            """;

    String sampleDocument7 = """
            <html>
                <body>
                    <div>
                        <div>
                            <label>Some label
                                <span>inner span</span>
                            </label>
                            <span>Element</span>
                        </div>
                        <div>
                            <label>Some label
                                <span>inner span</span>
                            </label>
                            <span>Element</span>
                        </div>
                    </div>
                </body>
            </html>
            """;

    String sampleXpath1 = "/html/body/div/div";
    String sampleXpath2 = "/html/body/div/div/span";
    String sampleXpath3 = "/html/body/div/div/label/span";

}
