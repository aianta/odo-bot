package ca.ualberta.odobot.semanticflow.ranking.terms.impl;


import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DijkstraCache {
    private static final Logger log = LoggerFactory.getLogger(DijkstraCache.class);

    private static int hits;
    private static int misses;

    private static String lastKey = null;

    private Map<String, DistanceCache> caches = new HashMap<>();
    private Map<String, Integer> age = new HashMap<>();

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

    public synchronized void cacheDistance(String html, Element source, Element target, Integer distance){
        //Don't let the cache grow too big
        if(caches.size() > 20){
            log.info("Cache grew too large, evicting a key.");
            String keyToKick = getLeastRecentlyUsedKey();
            if(keyToKick == null){
                log.warn("keyToKick was null!");
                log.warn("caching key: {}", html.substring(0, 500));
                caches.keySet().stream().forEach(key->log.info("{}", key.substring(0, 500)));
            }else{
                DistanceCache cacheToKick = caches.get(keyToKick);
                cacheToKick.clear();
                caches.remove(keyToKick);
            }

        }

        if(!age.containsKey(html)){
            age.put(html, 0);
        }
        DistanceCache cache = caches.getOrDefault(html, new DistanceCache());
        cache.cacheDistance(source, target, distance);
        caches.put(html, cache);
        if(lastKey != null && !lastKey.equals(html)){
            /*Updating age iterates through all age entries in the cache,
             since we cache things very often for the same key,
             we can just avoid updating the ages unless the key has changed since last time.
             */
            updateAge(html);
        }
        lastKey = html;
    }

    private synchronized String getLeastRecentlyUsedKey(){
        String leastRecentlyUsedKey = age.entrySet().stream()
                //Sort the age entries in descending order.
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toList())
                .get(0).getKey(); //Get the oldest key

        //Remove the key from the age map
        age.remove(leastRecentlyUsedKey);

        //And return it.
        return leastRecentlyUsedKey;

    }

    /**
     * Increases the age of all keys except the one passed to this method.
     * @param key
     */
    private synchronized void updateAge(String key){

        Map<String, Integer> newAgeMap = new HashMap<>();

        age.entrySet().stream()
                .filter(entry->!entry.getKey().equals(key))
                .forEach(
                ageEntry->{
                    newAgeMap.put(ageEntry.getKey(), ageEntry.getValue()+1);
                }
        );
        newAgeMap.put(key, age.get(key));

        age = newAgeMap;
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
