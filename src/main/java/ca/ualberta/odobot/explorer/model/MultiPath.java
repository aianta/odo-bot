package ca.ualberta.odobot.explorer.model;

import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * @author Alexandru Ianta
 * @date Feb 6 2024
 *
 * MultiPath allows {@link Operation}'s to easily implement multiple ways to achieving the same thing and
 * randomly selecting between those ways at runtime. The aim is to facilitate the production of variety
 * in the generated dataset, that more closely resembles real user interaction.
 */
public class MultiPath {

    private static final Random random = new Random();
    private static final Logger log = LoggerFactory.getLogger(MultiPath.class);

    List<Consumer<WebDriver>> options = new ArrayList<>();

    Consumer<WebDriver> fallback;

    /**
     * Set a specific path as fallback in case path fails with NoSuchElement selenium exceptions
     * @param fallback the fallback path to invoke.
     */
    public void setFallback(Consumer<WebDriver> fallback) {
        this.fallback = fallback;
    }

    public void addPath(Consumer<WebDriver> path){
        this.options.add(path);
    }

    /**
     * Returns a random path from the set of options added to this MultiPath.
     * If a fallback has been set {@link #setFallback(Consumer)}, the randomly
     * chosen path is wrapped in error handling that automatically engages the
     * fallback should a {@link NoSuchElementException} occur during path execution.
     * @return a randomly chosen path
     */
    public Consumer<WebDriver> getPath(){

        Consumer<WebDriver> path = options.get(random.nextInt(options.size()));

        if(fallback == null){
            return path;
        }

        Consumer<WebDriver> wrapper = driver -> {
            try{
                path.accept(driver);
            }catch (NoSuchElementException  notFound){
                log.warn("Random path failed with missing element, engaging fallback...");
                log.warn(notFound.getMessage(), notFound);
                fallback.accept(driver);
            }catch (ElementClickInterceptedException interceptedException){
                log.warn(interceptedException.getMessage(), interceptedException);
                fallback.accept(driver);
            }catch (Exception e){
                log.warn(e.getMessage(), e);
                fallback.accept(driver);
            }
        };


        return wrapper;
    }


}
