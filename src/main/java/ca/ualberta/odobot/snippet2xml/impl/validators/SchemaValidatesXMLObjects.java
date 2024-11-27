package ca.ualberta.odobot.snippet2xml.impl.validators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.function.Predicate;

public class SchemaValidatesXMLObjects implements Predicate<String> {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidatesXMLObjects.class);


    Collection<String> xmlObjects;

    public SchemaValidatesXMLObjects(Collection<String> xmlObjects){
        this.xmlObjects = xmlObjects;
    }

    /**
     * https://stackoverflow.com/questions/15732/how-to-validate-an-xml-file-against-an-xsd-file
     *
     * @param schemaCandidate the schema to test
     * @return true, if the schemaCandidate successfully validates all xmlObjects associated with this validator
     */
    @Override
    public boolean test(String schemaCandidate) {

        Source _schema = new StreamSource(new StringReader(schemaCandidate));
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

        try{
            Schema schema = schemaFactory.newSchema(_schema);
            Validator validator = schema.newValidator();

            //Need to init and set an ErrorHandler otherwise schema validation errors will not be reported when validatator.validate() is called.
            XSDErrorHandler errorHandler = new XSDErrorHandler();
            validator.setErrorHandler(errorHandler);

            for(String xmlObject:xmlObjects){
                validator.validate(new StreamSource(new StringReader(xmlObject)));
            }

            //If the error handler reports no errors (false) then the candidate schema passes the test.
            return !errorHandler.hasErrors();

        } catch (SAXException e) {
            log.error(e.getMessage(), e);
            return false;
            //throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


    }
}
