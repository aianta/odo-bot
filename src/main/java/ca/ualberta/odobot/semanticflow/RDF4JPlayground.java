package ca.ualberta.odobot.semanticflow;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.TreeModel;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class RDF4JPlayground {
    private static final Logger log = LoggerFactory.getLogger(RDF4JPlayground.class);

    public static void main (String args []) {
        test2();
    }

    public static void test2(){
        String serverUrl = "http://localhost:8080/rdf4j-server";
        RemoteRepositoryManager manager = new RemoteRepositoryManager(serverUrl);
        manager.init();

        Repository repo = manager.getRepository("test");
        try(RepositoryConnection conn = repo.getConnection()){

            Model model = new TreeModel();
            String ns = "http://localhost:8080/rdf-server/repositories/test";

            IRI picasso = Values.iri(ns, "Picasso");
            IRI artist = Values.iri(ns, "Artist");
            model.add(picasso, RDF.TYPE, artist);
            model.add(picasso, FOAF.FIRST_NAME, Values.literal("Pablo"));

            conn.add(model);

        }
    }

    public void test1(){
        String filename = "test_store.ttl";
        File file = new File(filename);
        File dataDir = new File ("test_store");
        try(FileInputStream fis = new FileInputStream(file);){
            InputStream input = fis;
            Model model = Rio.parse(input, "", RDFFormat.TURTLE);
            String ns = "http://example.org";

            IRI picasso = Values.iri(ns, "Picasso");
            IRI artist = Values.iri(ns, "Artist");
            model.add(picasso, RDF.TYPE, artist);
            model.add(picasso, FOAF.FIRST_NAME, Values.literal("Pablo"));

            Repository db = new SailRepository(new NativeStore(dataDir));

            try(RepositoryConnection conn = db.getConnection()){
                conn.add(model);
                conn.commit();

                try(RepositoryResult<Statement> result = conn.getStatements(null, null, null)){
                    for(Statement st: result){
                        log.info("db contains: {}", st);
                    }
                }


            }finally {
                db.shutDown();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

    }

}
