package ca.ualberta.odobot.semanticflow.ranking.terms.impl;


import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DijkstraCache {
    private static final Logger log = LoggerFactory.getLogger(DijkstraCache.class);

    private static int hits;
    private static int misses;

    private Map<String, DistanceCache> caches = new HashMap<>();

    private class DistanceCache{

        Map<Element, Map<Element, Integer>> distanceCache = new HashMap<>();

        public void clear(){
            distanceCache.clear();
        }

        public void cacheDistance(Element src, Element tgt, int distance){
            Map<Element, Integer> map = distanceCache.getOrDefault(src, new HashMap<>());
            map.put(tgt, distance);
            distanceCache.put(src, map);
        }

        public Optional<Integer> getDistance(Element src, Element tgt){
            Map<Element, Integer> map = distanceCache.get(src);
            if(map == null){
                log.warn("Source element not found in dijkstra cache!");
                misses++;
                return Optional.empty();
            }

            Integer distance = map.get(tgt);

            if(distance == null){
                log.warn("Target element not found in dijkstra cache!");
                misses++;
                return Optional.empty();
            }

            //log.info("Dijkstra cache hit!");
            hits++;
            return Optional.of(distance);

        }

    }

    public void printStats(){
        log.info("DijkstraCache hits: {} misses: {} ratio: {}", hits, misses, (double)hits/(double)misses);
    }

    public void cacheDistance(Document dom, Element source, Element target, Integer distance){
        cacheDistance(dom.toString(), source, target, distance);
    }

    public void cacheDistance(String html, Element source, Element target, Integer distance){
        //Don't let the cache grow too big
        //TODO - Implement some better logic here, maybe Least Recently Used
        if(caches.size() > 10){
            log.info("Cache grew too large, evicting a key.");
            String keyToKick = caches.keySet().stream()
                    .filter(key->!key.equals(html)) //Don't evict the key we're caching values for.
                    .iterator().next();
            DistanceCache cacheToKick = caches.get(keyToKick);
            cacheToKick.clear();
            caches.remove(keyToKick);
        }

        DistanceCache cache = caches.getOrDefault(html, new DistanceCache());
        cache.cacheDistance(source, target, distance);
        caches.put(html, cache);


    }

    public Optional<Integer> getDistance(Document dom, Element source, Element target){
        return getDistance(dom.toString(), source, target);
    }
    public Optional<Integer> getDistance(String html, Element source, Element target){

        DistanceCache cache = caches.get(html);
        if(cache == null){
            log.warn("No cache found for given document!");
            misses++;
            return Optional.empty();
        }

        return cache.getDistance(source, target);

    }



}
