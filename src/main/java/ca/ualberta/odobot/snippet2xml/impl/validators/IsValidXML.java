package ca.ualberta.odobot.snippet2xml.impl.validators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.function.Predicate;

/**
 * A predicate that tests if a string is valid XML.
 */
public class IsValidXML implements Predicate<String> {

    private static final Logger log = LoggerFactory.getLogger(IsValidXML.class);

    private DocumentBuilder builder;

    public IsValidXML(){
        try{
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            builder = factory.newDocumentBuilder();

        } catch (ParserConfigurationException e) {
            log.error("Error initializing DocumentBuilder");
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean test(String string) {

        try{
            StringReader sr = new StringReader(string);
            InputSource is = new InputSource(sr);
            Document document = builder.parse(is);
            return true;
        }catch (SAXException parseError){
            return false;
        } catch (IOException e) {
            log.error("IO error while attempting to parse XML...");
            throw new RuntimeException(e);
        }

    }
}