import ca.ualberta.odobot.semanticflow.Coordinate;
import ca.ualberta.odobot.semanticflow.Graph;
import ca.ualberta.odobot.semanticflow.ModelManager;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CoordinateMergeTest {
    private static final Logger log = LoggerFactory.getLogger(CoordinateMergeTest.class);

    @Test
    void mergeTest(){
        Coordinate source = makeSource();
        Coordinate target = makeTarget();

        log.info("source: {}", source);
        log.info("target: {}", target);

        Graph sourceGraph = source.toGraph();
        Graph targetGraph = target.toGraph();

        Graph resultGraph = Graph.merge(sourceGraph, targetGraph);


        Coordinate result = resultGraph.toCoordinate();
        log.info(result.toString());
        log.info(resultGraph.toString());

    }

    public Coordinate makeSource(){

        Coordinate a = new Coordinate();

        a.xpath = "/a";
        a.index = 0;
        a.parent = null;

        Coordinate b = new Coordinate();

        b.xpath ="/a/b";
        b.index = 0;
        b.parent = a;

        a.addChild(b);

        Coordinate c = new Coordinate();

        c.xpath = "/a/b/c";
        c.index = 0;
        c.parent = b;

        b.addChild(c);

        Coordinate e = new Coordinate();

        e.xpath = "/a/e";
        e.index = 0;
        e.parent = a;

        a.addChild(e);

        Coordinate g = new Coordinate();

        g.xpath = "/a/e/g";
        g.index = 0;
        g.parent = e;

        e.addChild(g);

        return a;
    }

    public Coordinate makeTarget(){

        Coordinate a = new Coordinate();

        a.xpath = "/a";
        a.index = 0;
        a.parent = null;

        Coordinate b = new Coordinate();

        b.xpath ="/a/b";
        b.index = 0;
        b.parent = a;

        a.addChild(b);

        Coordinate c = new Coordinate();

        c.xpath = "/a/b/c";
        c.index = 0;
        c.parent = b;

        b.addChild(c);

        Coordinate d = new Coordinate();

        d.xpath = "/a/b/d";
        d.index = 0;
        d.parent = b;

        b.addChild(d);


        return a;
    }

}
