package ca.ualberta.odobot.semanticflow.statemodel;

public class Edge {
    public String source;
    public String target;

    public Edge(String source, String target){
        this.source = source;
        this.target = target;
    }

    public int hashCode(){
        return source.hashCode() ^ target.hashCode();
    }

    public boolean equals(Object o){
        Edge other = (Edge)o;
        return this.source.equals(other.source) && this.target.equals(other.target);
    }

    public String toString(){
        return "(" + source + "," + target + ")";
    }
}
