package ca.ualberta.odobot.explorer.canvas.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 *
 * @Author Alexandru Ianta
 *
 * Responsible for loading relevant course information from IMSCC course files.
 */
public class ResourceManager {

   private static final Logger log = LoggerFactory.getLogger(ResourceManager.class);
   private static XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();


    /**
     *
     * @param imsccPath path to the IMSCC file containing the course data.
     * @return A {@link CourseResources} object containing the extracted course objects.
     */
    public static CourseResources loadCourse(String imsccPath){

        File courseArchive = new File(imsccPath);

        File courseContentDir = unzipArchive(courseArchive);

        log.info("Unzipped course content into temporary dir at: {}", courseContentDir.getPath());

        File imsManifestFile = new File(courseContentDir.getPath() + File.separator + "imsmanifest.xml");

        log.info("Reading {}", imsManifestFile.getPath());

        Course course = new Course();

        XMLResourceReader imsManifestReader = new XMLResourceReader();
        imsManifestReader.addHandler(path-> path.get(path.size()-1).equals("lomimscc:string") && path.get(path.size()-2).equals("lomimscc:title"), xmlEvent -> course.setName(xmlEvent.asCharacters().getData()));
        readXMLFile(imsManifestFile, imsManifestReader::consume);

        log.info("Loaded course {}", course.getName());



        return null;
    }

    private static class XMLResourceReader{


        private Map<Predicate<Stack<String>>, Consumer<XMLEvent>> handlers = new HashMap<>();
        private Stack<String> path = new Stack();

        public XMLResourceReader addHandler(Predicate<Stack<String>> predicate, Consumer<XMLEvent> handler){
            handlers.put(predicate, handler);
            return this;
        }

        Set<Consumer<XMLEvent>> fireOnNextEvent = new HashSet<>();

        public void consume(XMLEvent event){
            if(event.isCharacters() && fireOnNextEvent.size() > 0){
                for(Consumer<XMLEvent> handler: fireOnNextEvent){
                    handler.accept(event);
                }
                fireOnNextEvent.clear();
            }

            if(event.isStartElement()){
                StartElement startElement = event.asStartElement();
                String elementName = computeName(startElement);
                path.push(elementName);
                log.info(elementName);

                for(Predicate<Stack<String>> predicate: handlers.keySet()){
                    if(predicate.test(path)){
                        fireOnNextEvent.add(handlers.get(predicate));
                    }
                }

            }

            if(event.isEndElement()){
                EndElement endElement = event.asEndElement();
                String elementName = computeName(endElement);
                if(!path.peek().equals(elementName)){
                    log.error("Path stack error! Expected {} but got: {}", path.peek(), elementName);
                    throw new RuntimeException("Path stack error! Expected "+path.peek()+" but got: "+elementName+"" );
                }
                path.pop();
            }
        }

        private String computeName(StartElement startElement){
            return startElement.getName().getPrefix() + ":" + startElement.getName().getLocalPart();
        }

        private String computeName(EndElement endElement){
            return endElement.getName().getPrefix() + ":" + endElement.getName().getLocalPart();
        }


    }


    /**
     * Reads an XML file as a stream of XMLEvents that are passed to a specified consumer.
     * @param f The XML file to read.
     * @param consumer The consumer of the XMLEvents.
     */
    private static void readXMLFile(File f, Consumer<XMLEvent> consumer){

        try(FileInputStream fis = new FileInputStream(f.getPath())){
            XMLEventReader reader = xmlInputFactory.createXMLEventReader(fis);

            while (reader.hasNext()){
                XMLEvent nextEvent = reader.nextEvent();
                consumer.accept(nextEvent);
            }

        } catch (XMLStreamException e) {
            throw new RuntimeException(e);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


    }



    /**
     *
     * @param archive IMSCC file to decompress
     * @return the temporary directory in which the IMSCC file has been extracted.
     */
    private static File unzipArchive(File archive){
        File outputDir = null;
        try{
            //Create a temporary directory into which we unzip the IMSCC archive.
            outputDir = Files.createTempDirectory("odo-bot-course").toFile();
            outputDir.deleteOnExit();

            /**
             *
             * Notes on the logic, and how to prevent zip slip below.
             * https://www.baeldung.com/java-compress-and-uncompress
             *
             * Related information on how to implement unzipping in java 9+
             * https://stackoverflow.com/questions/9324933/what-is-a-good-java-library-to-zip-unzip-files
             */
            try(ZipFile zipFile = new ZipFile(archive)){
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()){
                    ZipEntry entry = entries.nextElement();
                    File entryDestination = new File(outputDir, entry.getName());
                    if(entry.isDirectory()){
                        entryDestination.mkdirs();
                    }else{

                        //Prevent ZipSlip by ensuring that the output path is inside the extracted folder.
                        String outputDirPath = outputDir.getCanonicalPath();
                        String destFilePath = entryDestination.getCanonicalPath();

                        if(!destFilePath.startsWith(outputDirPath + File.separator)){
                            throw new IOException("Entry is outside of target dir: " + entry.getName());
                        }

                        entryDestination.getParentFile().mkdirs();
                        zipFile.getInputStream(entry).transferTo(new FileOutputStream(entryDestination));
                    }
                }


            }catch (IOException e){
                log.error("Error decompressing archive.");
                log.error(e.getMessage(),e);

            }


        }catch (IOException e){
            log.error(e.getMessage(), e);
        }


        return outputDir;

    }


}
