package ca.ualberta.odobot.semanticflow;

import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.condition.crawlcondition.CrawlCondition;
import com.crawljax.core.CandidateElement;
import com.crawljax.core.CrawlerContext;
import com.crawljax.core.CrawljaxRunner;
import com.crawljax.core.configuration.BrowserConfiguration;
import com.crawljax.core.configuration.CrawlRules;
import com.crawljax.core.configuration.CrawljaxConfiguration;
import com.crawljax.core.configuration.InputSpecification;
import com.crawljax.core.plugin.OnNewStatePlugin;
import com.crawljax.core.plugin.PreStateCrawlingPlugin;
import com.crawljax.core.state.Identification;
import com.crawljax.core.state.StateVertex;
import com.crawljax.forms.FormInput;
import com.crawljax.plugins.crawloverview.CrawlOverview;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class CrawljaxPlayground implements PreStateCrawlingPlugin {

    private static final Logger log = LoggerFactory.getLogger(CrawljaxPlayground.class);

    public static void main (String args []){

        //Setup config
        CrawljaxConfiguration.CrawljaxConfigurationBuilder builder = CrawljaxConfiguration.builderFor("http://www.reddit.com");

        InputSpecification input = new InputSpecification();
//        input.inputField(FormInput.InputType.INPUT, new Identification(Identification.How.id, "pseudonym_session_unique_id")).inputValues("ianta@ualberta.ca");
//        input.inputField(FormInput.InputType.INPUT, new Identification(Identification.How.id, "pseudonym_session_password")).inputValues("01134hello");

        builder.crawlRules().setInputSpec(input);
        builder.crawlRules().crawlHiddenAnchors(false);

        builder.crawlRules().click("button");
        builder.crawlRules().click("a").when((browser)->!browser.elementExists(new Identification(Identification.How.id, "pseudonym_session_unique_id")));
        builder.setBrowserConfig(new BrowserConfiguration(EmbeddedBrowser.BrowserType.EDGE, 1));
        builder.setOutputDirectory(new File("crawljax_out"));

        builder.addPlugin(new CrawlOverview());
        builder.addPlugin(new CrawljaxPlayground());
        builder.setMaximumDepth(10);
        builder.setUnlimitedRuntime();
        builder.setMaximumStates(5);



        CrawljaxRunner crawljax = new CrawljaxRunner(builder.build());
        crawljax.call();




    }


    @Override
    public void preStateCrawling(CrawlerContext context, ImmutableList<CandidateElement> candidateElements, StateVertex state) {
        candidateElements.forEach(element->{
            log.info("element: <{}> - {}", element.getElement().getTagName(), element.getElement().getTextContent());
        });
    }
}
