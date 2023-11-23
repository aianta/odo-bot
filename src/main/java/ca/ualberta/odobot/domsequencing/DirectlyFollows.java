package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class DirectlyFollows {

    private static final Logger log = LoggerFactory.getLogger(DirectlyFollows.class);
    String source;

    Map<String, Integer> successors = new TreeMap<>();

    public DirectlyFollows(String source){
        this.source = source;
    }

    public void merge(DirectlyFollows record){
        record.successors.forEach((successor, theirCount)->{
            int myCount = this.successors.getOrDefault(successor,0);
            int newCount = myCount + theirCount;
            this.successors.put(successor, newCount);
        });
    }

    public void addSuccessor(Element e){

        Set<String> classes = e.classNames();
        classes.forEach(c->{
            int count = successors.getOrDefault(c, 0);
            count += 1;
            successors.put(c, count);
        });

    }

}
