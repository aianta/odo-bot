package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

public class CSSClassTree {

    public String name;

    public CSSClassTree parent = null;

    public List<CSSClassTree> children = new ArrayList<>();

    public CSSClassTree(String name){
        this.name = name;
    }

}
