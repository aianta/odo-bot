package ca.ualberta.odobot.snippet2xml.impl.validators;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * A simple Error handler implementation that simply counts schema validation errors and
 * provides a convenience method to determine if errors have occurred.
 */
public class XSDErrorHandler implements ErrorHandler {
    public int warnings = 0;
    public int errors = 0;
    public int fatalErrors = 0;

    @Override
    public void warning(SAXParseException exception) throws SAXException {
        warnings++;
    }

    @Override
    public void error(SAXParseException exception) throws SAXException {
        errors++;
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
        fatalErrors++;
    }

    public boolean hasErrors(){
        return (fatalErrors + errors) > 0;
    }
}
