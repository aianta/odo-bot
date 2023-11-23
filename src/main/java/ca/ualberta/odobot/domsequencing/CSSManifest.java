package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;

import java.util.*;

public class CSSManifest extends TreeMap<String, CSSClass> {

    public void merge(CSSManifest manifest){
        manifest.forEach((c, cssClass)->{
            CSSClass record = getOrDefault(c, new CSSClass(c));
            record.instances.addAll(cssClass.instances);
            put(c, record);
        });
    }

    public void catalogElement(Element e){
        Set<String> classes = e.classNames();
        classes.forEach(c->{
            CSSClass record = getOrDefault(c, new CSSClass(c));
            record.instances.add(e);
            put(c, record);
        });
    }

    public String toString(){
        StringBuilder sb = new StringBuilder();
        forEach((c,cssClass)->{
            sb.append(c + ": " + cssClass.instances.size() + " instances\n");
        });
        return sb.toString();
    }
}
