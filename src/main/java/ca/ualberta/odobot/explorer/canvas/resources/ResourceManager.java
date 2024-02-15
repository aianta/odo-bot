package ca.ualberta.odobot.explorer.canvas.resources;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

   private static final Pattern PAGE_IDENTIFIER_IN_HTML = Pattern.compile("(?<=<meta name=\"identifier\" content=\")[a-zA-Z0-9]+(?=\"\\/>)");


    /**
     *
     * @param imsccPath path to the IMSCC file containing the course data.
     * @return A {@link CourseResources} object containing the extracted course objects.
     */
    public static CourseResources loadCourse(String imsccPath){

        CourseResources result = new CourseResources();

        File courseArchive = new File(imsccPath);

        File courseContentDir = unzipArchive(courseArchive);

        log.info("Unzipped course content into temporary dir at: {}", courseContentDir.getPath());

        File imsManifestFile = new File(courseContentDir.getPath() + File.separator + "imsmanifest.xml");
        File moduleMetaFile = new File(courseContentDir.getPath() + File.separator + "course_settings" + File.separator + "module_meta.xml");

        log.info("Reading {}", imsManifestFile.getPath());

        Course course = new Course();

        XMLResourceReader imsManifestReader = new XMLResourceReader();
        imsManifestReader.addStartHandler(path->path.get(path.size()-1).equals(":manifest"), (start,next)->course.setIdentifier(start.getAttributeByName(new QName("identifier")).getValue()));
        imsManifestReader.addStartHandler(path-> path.get(path.size()-1).equals("lomimscc:string") && path.get(path.size()-2).equals("lomimscc:title"), (start, characters) -> course.setName(characters.asCharacters().getData()));
        readXMLFile(imsManifestFile, imsManifestReader::consume);

        log.info("Loaded course {}", course.getName());

        result.setCourse(course);

        List<String> moduleNames = new ArrayList<>();
        List<String> moudleIdentifiers = new ArrayList<>();

        List<String> itemTypes = new ArrayList<>();
        List<String> itemIdentifiers = new ArrayList<>();
        List<String> itemIdentifierRefs = new ArrayList<>();
        List<String> itemTitles = new ArrayList<>();

        XMLResourceReader moduleMetaReader = new XMLResourceReader();
        moduleMetaReader.addStartHandler(path->path.get(path.size()-1).equals(":module") && path.get(path.size()-2).equals(":modules"), (start, next)-> moudleIdentifiers.add(start.getAttributeByName(new QName("identifier")).getValue()));
        moduleMetaReader.addStartHandler(path->path.get(path.size()-1).equals(":title") && path.get(path.size()-2).equals(":module"), (start, characters) -> moduleNames.add(characters.asCharacters().getData()));
        moduleMetaReader.addStartHandler(path->path.get(path.size()-1).equals(":content_type") && path.get(path.size()-2).equals(":item") && path.get(path.size()-3).equals(":items"), (start, characters)->itemTypes.add(characters.asCharacters().getData()));
        moduleMetaReader.addStartHandler(path->path.get(path.size()-1).equals(":item") && path.get(path.size()-2).equals(":items"), (start, next)->itemIdentifiers.add(start.getAttributeByName(new QName("identifier")).getValue()));
        moduleMetaReader.addStartHandler(path->path.get(path.size()-1).equals(":identifierref") && path.get(path.size()-2).equals(":item") && path.get(path.size()-3).equals(":items"), (start, characters)->itemIdentifierRefs.add(characters.asCharacters().getData()));
        moduleMetaReader.addStartHandler(path->path.get(path.size()-1).equals(":title") && path.get(path.size()-2).equals(":item") && path.get(path.size()-3).equals(":items"), (start, characters)->itemTitles.add(characters.asCharacters().getData()));
        moduleMetaReader.addEndHandler(path->path.get(path.size()-1).equals(":item") && path.get(path.size()-2).equals(":items"), (event)->{
           if(itemIdentifierRefs.size() != itemTypes.size()){
               itemIdentifierRefs.add(null);
           }
           if(itemIdentifierRefs.size() != itemTypes.size()){
               throw new RuntimeException("Error parsing module_meta.xml!");
           }
        });
        readXMLFile(moduleMetaFile, moduleMetaReader::consume);

        Iterator<String> moduleNameIt = moduleNames.iterator();
        Iterator<String> moduleIdentifiersIt = moudleIdentifiers.iterator();

        while (moduleNameIt.hasNext()){
            Module module = new Module();
            module.setName(moduleNameIt.next());
            module.setIdentifier(moduleIdentifiersIt.next());
            result.addModule(module);
        }

        Iterator<String> itemTypeIt = itemTypes.iterator();
        Iterator<String> itemIdentifiersIt = itemIdentifiers.iterator();
        Iterator<String> itemIdentifierRefsIt = itemIdentifierRefs.iterator();
        Iterator<String> itemTitlesIt = itemTitles.iterator();

        while (itemTypeIt.hasNext()){
            String itemType = itemTypeIt.next();
            String itemIdentifier = itemIdentifiersIt.next();
            String itemIdentifierRef = itemIdentifierRefsIt.next();
            String itemTitle = itemTitlesIt.next();

            switch (itemType){
                case "WikiPage":
                    Page page = new Page();
                    page.setTitle(itemTitle);
                    page.setIdentifier(itemIdentifier);
                    page.setIdentifierRef(itemIdentifierRef);
                    result.addPage(page);
                    break;
                case "Quizzes::Quiz":
                    Quiz quiz = new Quiz();
                    quiz.setName(itemTitle);
                    quiz.setBody(itemTitle + " quiz");
                    quiz.setIdentifier(itemIdentifier);
                    quiz.setIdentifierRef(itemIdentifierRef);
                    result.addQuiz(quiz);
                    break;
                case "Assignment":
                    Assignment assignment = new Assignment();
                    assignment.setName(itemTitle);
                    assignment.setIdentifier(itemIdentifier);
                    assignment.setIdentifierRef(itemIdentifierRef);
                    result.addAssignment(assignment);
                    break;
            }


        }



        loadPageContent(courseContentDir, result);
        loadQuizContent(courseContentDir, result);
        loadAssignments(courseContentDir, result);

        log.info(result.contents());

        return result;
    }

    private static CourseResources loadAssignments(File courseContentDir, CourseResources resources){

        resources.assignments().forEach(assignment -> {

            File assignmentFolder = new File(courseContentDir.getPath() + File.separator + assignment.getIdentifierRef());
            File assignmentFile = new File(assignmentFolder.getPath() + File.separator + "assignment.xml");


            XMLResourceReader assignmentReader = new XMLResourceReader();
            assignmentReader.addStartHandler(path->path.get(path.size()-1).equals(":text") && path.get(path.size()-2).equals(":assignment"), (start,characters)->{
               assignment.setBody(Jsoup.parse(characters.asCharacters().getData()).text());
            });
            readXMLFile(assignmentFile, assignmentReader::consume);

        });
        return resources;
    }

    private static CourseResources loadQuizContent(File courseContentDir, CourseResources resources){

        resources.quizzes().forEach(quiz -> {
            File quizFolder = new File(courseContentDir.getPath() + File.separator + quiz.getIdentifierRef());

            File questionsFile = new File(quizFolder.getPath() + File.separator + "assessment_qti.xml");

            List<String> questionTexts = new ArrayList<>();
            List<String> questionIdentifiers = new ArrayList<>();
            List<String> questionTitles = new ArrayList<>();

            XMLResourceReader questionsReader = new XMLResourceReader();
            questionsReader.addStartHandler(path->path.get(path.size()-1).equals(":item"), (start,next)->{
                questionIdentifiers.add(start.getAttributeByName(new QName("ident")).getValue());
                questionTitles.add(start.getAttributeByName(new QName("title")).getValue());
            });
            questionsReader.addStartHandler(path->path.get(path.size()-1).equals(":mattext") && path.get(path.size()-2).equals(":material") && path.get(path.size()-3).equals(":presentation"), (start, characters)->questionTexts.add(characters.asCharacters().getData()));
            readXMLFile(questionsFile, questionsReader::consume);

            Iterator<String> questionTextIt = questionTexts.iterator();
            Iterator<String> questionTitleIt = questionTitles.iterator();
            ListIterator<String> questionIdentifierIt = questionIdentifiers.listIterator();
            while (questionTextIt.hasNext()){
                QuizQuestion question = new QuizQuestion();

                String questionName = questionTitleIt.next();
                String questionIdentifier = questionIdentifierIt.next();

                question.setName(questionName);
                question.setIdentifier(questionIdentifier+"#"+questionIdentifierIt.previousIndex());
                question.setType(QuizQuestion.QuestionType.MULTIPLE_CHOICE);
                question.setBody(
                        Jsoup.parse(questionTextIt.next()).text()  //This is necessary to convert HTML entities into their real character values. IE: &lt; into <
                );
                question.setName("Question");
                question.setRelatedQuizIdentifier(quiz.getIdentifier());
                resources.addQuestion(question);
            }

        });

        return resources;
    }


    /**
     * Goes through all the pages in the wiki_content folder and loads them to the corresponding page
     * records.
     * @param courseContentDir The temporary directory into which the IMSCC file was extracted
     * @param resources The course resources object being constructed.
     * @return
     */
    private static CourseResources loadPageContent(File courseContentDir, CourseResources resources){
        try{
            File wikiContentDir = new File(courseContentDir.getPath() + File.separator + "wiki_content");

            File [] files = wikiContentDir.listFiles();

            for(File page: files){

                String content = new String(Files.readAllBytes(page.toPath()));
                Matcher matcher =  PAGE_IDENTIFIER_IN_HTML.matcher(content);
                if(matcher.find()){
                    String identifierRefInHTML = matcher.group();
                    //log.info("looking for: {}", identifierRefInHTML);
                    Page target = resources.getPageByIdentifierRef(identifierRefInHTML);
                    if(target != null){
                        target.setBody(Jsoup.parse(content).text());
                    }



                }



            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }



        return resources;

    }

    private static class XMLResourceReader{


        private Map<Predicate<Stack<String>>, BiConsumer<StartElement,XMLEvent>> startHandlers = new HashMap<>();
        private Map<Predicate<Stack<String>>, Consumer> endHandlers = new HashMap<>();
        private Stack<String> path = new Stack();

        public XMLResourceReader addStartHandler(Predicate<Stack<String>> predicate, BiConsumer<StartElement,XMLEvent> handler){
            startHandlers.put(predicate, handler);
            return this;
        }

        public XMLResourceReader addEndHandler(Predicate<Stack<String>> predicate, Consumer handler){
            this.endHandlers.put(predicate, handler);
            return this;
        }

        Set<BiConsumer<StartElement,XMLEvent>> fireOnNextEvent = new HashSet<>();

        StartElement startElement;

        public void consume(XMLEvent event){
            if(event.isCharacters() && fireOnNextEvent.size() > 0){
                for(BiConsumer<StartElement,XMLEvent> handler: fireOnNextEvent){
                    handler.accept(startElement,event);
                }
                fireOnNextEvent.clear();
            }

            if(event.isStartElement()){
                startElement = event.asStartElement();
                String elementName = computeName(startElement);
                path.push(elementName);

                for(Predicate<Stack<String>> predicate: startHandlers.keySet()){
                    if(predicate.test(path)){
                        fireOnNextEvent.add(startHandlers.get(predicate));
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
                for(Predicate<Stack<String>> predicate: endHandlers.keySet()){
                    if(predicate.test(path)){
                        endHandlers.get(predicate).accept(event);
                    }
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
