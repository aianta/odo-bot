package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.snippet2xml.impl.Snippet2XMLServiceImpl;
import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ProxyGen
public interface Snippet2XMLService {

    /**
     * Helper method that extracts a {@link SemanticSchema} object from the JsonObject return value of {@link #makeSchema(List)}
     * @param json
     * @return
     */
    static SemanticSchema extractNewSchema(JsonObject json){

        SemanticSchema result = new SemanticSchema();
        result.setSchema(json.getString("schema"));
        result.setId(UUID.randomUUID());
        result.setDynamicXpathId(json.getString("dynamicXpath"));

        assert result.getDynamicXpathId() != null;

        try{
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            StringReader sr = new StringReader(result.getSchema());
            InputSource is = new InputSource(sr);

            Document document = factory.newDocumentBuilder().parse(is);

            Optional<String> name = getElementNodeNameAttribute(document);
            if(name.isPresent()){
                result.setName(name.get());
            }



        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        }

        return result;

    }

    private static Optional<String> getElementNodeNameAttribute(Document document){
        Node firstChild = document.getDocumentElement().getFirstChild();
        Node curr = firstChild;
        while (curr != null && !curr.getNodeName().equals("xs:element")){
            curr = curr.getNextSibling();
        }

        if(curr != null && curr.getNodeName().equals("xs:element") && curr.hasAttributes()){
            Node nameAttr = curr.getAttributes().getNamedItem("name");
            if(nameAttr != null){
                return Optional.of(nameAttr.getNodeValue());
            }
        }

        return Optional.empty();
    }

    /**
     * Helper method for extracting {@link SemanticObject}s from the return value of {@link #makeSchema(List)}.
     * @param json The JsonObject returned by {@link #makeSchema(List)}
     * @param schema The {@link SemanticSchema} returned by running {@link #extractNewSchema(JsonObject)} on the JsonObject returned by {@link #makeSchema(List)}
     * @return
     */
    static List<SemanticObject> extractSemanticObjects(JsonObject json, SemanticSchema schema){
        List<SemanticObject> result = new ArrayList<>();
        json.forEach(entry->{
            if(!entry.getKey().equals("schema") && !entry.getKey().equals("dynamicXpath")){
                SemanticObject object = new SemanticObject();
                object.setSnippetId(UUID.fromString(entry.getKey()));
                object.setObject((String)entry.getValue());
                object.setSchemaId(schema.getId());
                object.setId(UUID.randomUUID());

                result.add(object);
            }
        });

        return result;
    }

    static Snippet2XMLService create(Vertx vertx, JsonObject config, Strategy strategy){return  new Snippet2XMLServiceImpl(vertx, config, strategy);}

    static Snippet2XMLService createProxy(Vertx vertx, String address){
        return new Snippet2XMLServiceVertxEBProxy(vertx, address);
    }

    Future<SemanticObject> getObjectFromSnippet(Snippet snippet, SemanticSchema schema);

    Future<SemanticObject> getObjectFromHTML(String html, SemanticSchema schema);

    Future<SemanticObject> getObjectFromHTMLIgnoreSchemaIssues(String html, SemanticSchema schema);

    /**
     * Provide a set of snippets from which to generate an XML schema
     * @param snippets
     * @return A json object containing the generated schema under the field 'schema', the dynamic xpath used to generate the schema under the field 'dynamicXpath', and the XML objects of the snippets used to generate the schema.
     */
    Future<JsonObject> makeSchema(List<Snippet> snippets);

    Future<SemanticObject> pickParameterValue(List<SemanticObject> options, String query);

}
