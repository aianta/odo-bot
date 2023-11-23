package ca.ualberta.odobot.domsequencing;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.jsoup.nodes.Element;

import javax.swing.text.html.CSS;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class CSSClass implements Comparable<CSSClass> {

    public CSSClass(String name){
        this.name = name;
    }

    String name;

    List<Element> instances = new ArrayList<>();

    public String toString(){
        StringBuilder sb = new StringBuilder();

        sb.append(name + ":\n");
        Iterator<Element> it = instances.iterator();
        while (it.hasNext()){
            Element curr = it.next();
            sb.append(curr.outerHtml());

            if(it.hasNext()){
                sb.append("\n-------------------------------------\n");
            }
        }

        return sb.toString();
    }

    public int hashCode(){
        return new HashCodeBuilder(9,31).append(name).toHashCode();
    }

    public boolean equals(Object o){
        if(!(o instanceof CSSClass)){
            return false;
        }
        CSSClass other = (CSSClass) o;
        return this.name.equals(other.name);
    }

    public int compareTo(CSSClass other){
        return this.name.compareTo(other.name);
    }


}
