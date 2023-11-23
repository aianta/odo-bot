package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.TreeMap;

public class DirectlyFollowsManifest extends TreeMap<String,DirectlyFollows> {

    private static final Logger log = LoggerFactory.getLogger(DirectlyFollowsManifest.class);

    public String toString(){
        StringBuilder sb = new StringBuilder();

        forEach((c, record)->{
            sb.append(record.source + "\n");
            record.successors.forEach((successor,count)->{
                sb.append(String.format("\t%5d:%s\n", count, successor) );
            });
        });
        return sb.toString();
    }

    public void merge(DirectlyFollowsManifest manifest){
        manifest.forEach((c,theirRecord)->{
            DirectlyFollows myRecord = getOrDefault(c, new DirectlyFollows(c));
            myRecord.merge(theirRecord);
            put(c, myRecord);
        });
    }

    public void catalogElement(Element element){
        Element parentElement = element.parent();
        if(parentElement != null){
            Set<String> parentClasses = parentElement.classNames();
            parentClasses.forEach(c->{
                DirectlyFollows record = getOrDefault(c, new DirectlyFollows(c));
                record.addSuccessor(element);
                put(c, record);
            });
        }
    }
}
