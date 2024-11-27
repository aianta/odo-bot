package ca.ualberta.odobot.snippet2xml.impl.validators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.StringReader;
import java.util.function.Predicate;

public class PassesSchemaValidation implements Predicate<String> {

    private static final Logger log = LoggerFactory.getLogger(PassesSchemaValidation.class);

    private Source _schema;
    private SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

    private Schema schema;

    private XSDErrorHandler errorHandler;

    public PassesSchemaValidation(String schema){

        try {
            _schema = new StreamSource(new StringReader(schema));
            this.schema = schemaFactory.newSchema(_schema);

        } catch (SAXException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean test(String xml) {

        try {
            Validator validator = schema.newValidator();

            //Have to init and set an error handler otherwise schema validation errors are not reported when validator.validate() is executed.
            XSDErrorHandler errorHandler = new XSDErrorHandler();
            validator.setErrorHandler(errorHandler);

            validator.validate(new StreamSource(new StringReader(xml)));

            //If the error handler reports no errors (false) then the xml passes the test.
            return !errorHandler.hasErrors();

        } catch (SAXException e) {
            log.error(e.getMessage(), e);
            return false;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
