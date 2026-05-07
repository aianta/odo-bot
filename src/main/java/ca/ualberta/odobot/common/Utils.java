package ca.ualberta.odobot.common;

import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.util.CoreMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.*;
import java.util.*;
import java.util.function.Predicate;


public class Utils {

    public static String getTerminalElementOfXpath(String xpath){
        return xpath.substring(xpath.lastIndexOf("/"));
    }

    public static String normalizeBaseUri(String baseUri) {
        return baseUri.replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");
    }

    public static String splitCamelCase(String s) {

        return s.replaceAll(
                String.format("%s|%s|%s",
                        "(?<=[A-Z])(?=[A-Z][a-z])",
                        "(?<=[^A-Z])(?=[A-Z])",
                        "(?<=[A-Za-z])(?=[^A-Za-z])"
                ),
                " "
        );
    }





    public static String normalizeBaseUriV2(String baseUri){
        final URL url;
        try {
            url = new URI(baseUri).normalize().toURL();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        String path = url.getPath();
        String normalizedPath = path.replaceAll("(?<=/)[0-9]+(?=/|)","*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");;

        SortedMap<String,String> queryParameters = NormalizeURL.createParameterMap(url.getQuery());
        SortedMap<String, String> normalizedQueryParameters = new TreeMap<>();
        if(queryParameters != null){
            queryParameters.forEach((key,value)->{
                normalizedQueryParameters.put(key,"*");
            });
        }

        final int port = url.getPort();
        final String queryString;

        if(queryParameters != null){
            queryString = "?" + NormalizeURL.canonicalize(normalizedQueryParameters);
        }else{
            queryString = "";
        }

        return url.getProtocol() + "://" + url.getHost()
                + (port != -1 && port != 80 ? ":" + port : "")
                + normalizedPath + queryString;

    }
}
