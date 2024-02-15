package ca.ualberta.odobot.explorer.canvas.resources;


import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Alexandru Ianta
 *
 * A container class holding all the resources of a course.
 */
public class CourseResources {

    private Course course;

    private List<Module> modules = new ArrayList<>();

    private List<Page> pages = new ArrayList<>();

    private List<Quiz> quizzes = new ArrayList<>();

    private List<QuizQuestion> quizQuestions = new ArrayList<>();

    private List<Assignment> assignments = new ArrayList<>();

    public CourseResources addQuestion(QuizQuestion question){
        this.quizQuestions.add(question);
        return this;
    }

    public CourseResources addAssignment(Assignment a){
        this.assignments.add(a);
        return this;
    }

    public CourseResources addQuiz(Quiz q){
        this.quizzes.add(q);
        return this;
    }

    public CourseResources addPage(Page p){
        this.pages.add(p);
        return this;
    }

    public CourseResources setCourse(Course c){
        this.course = c;
        return this;
    }

    public CourseResources addModule(Module m){
        this.modules.add(m);
        return this;
    }

    public List<Module> modules(){
        return modules;
    }

    public List<QuizQuestion> questions(){
        return quizQuestions;
    }


    public Course getCourse(){
        return course;
    }

    public String contents(){

        long pagesWithContent = pages.stream().filter(p->p.getBody() != null).count();
        long assignmentsWithContent = assignments.stream().filter(a->a.getBody() != null).count();

        return "Modules: " + modules.size() + " Assignments: " + assignments.size() + " ["+assignmentsWithContent+"] Quizzes: " + quizzes.size() + " Pages: " + pages.size() + " ["+pagesWithContent+"]" + " Quiz Questions: " + quizQuestions.size() ;
    }

    public List<Assignment> assignments(){
        return assignments;
    }

    public List<Page> pages(){
        return pages;
    }

    /**
     *
     * @param identifier the IMSCC identifierRef as it appears in the exported course data.
     * @return The page corresponding with the specified IMSCC identifierRef.
     *
     * NOTE: This is not the Id of the page on Canvas.
     */
    public Page getPageByIdentifierRef(String identifier){
        return pages.stream().filter(page -> page.getIdentifierRef().equals(identifier)).findFirst().orElseGet(()->null);
    }

    public Quiz getQuizByIdentifier(String identifier){
        return quizzes.stream().filter(quiz -> quiz.getIdentifier().equals(identifier)).findFirst().orElse(null);
    }

    public List<QuizQuestion> getQuizQuestions(Quiz quiz){
        return quizQuestions.stream().filter(question->question.getRelatedQuizIdentifier().equals(quiz.getIdentifier())).collect(Collectors.toList());
    }

    public List<Quiz> quizzes(){
        return quizzes;
    }

    public Page getPageIdentifier(String identifier){
        return pages().stream().filter(page->page.getIdentifier().equals(identifier)).findFirst().orElse(null);
    }

    public Module getModuleByIdentifier(String identifier){
        return modules.stream().filter(module -> module.getIdentifier().equals(identifier)).findFirst().orElse(null);
    }

    public QuizQuestion getQuizQuestionByIdentifier(String identifier){
        return questions().stream().filter(question->question.getIdentifier().equals(identifier)).findFirst().orElse(null);
    }

    public Assignment getAssignmentByIdentifier(String identifier){
        return assignments.stream().filter(assignment->assignment.getIdentifier().equals(identifier)).findFirst().orElse(null);
    }

    public JsonObject getRuntimeValues(){
        JsonObject result = new JsonObject();

        if(course.getRuntimeData().size() > 0){
            result.put(course.getIdentifier(), course.getRuntimeData());
        }

        assignments.forEach(assignment->{
            if(assignment.getRuntimeData().size() > 0){
                result.put(assignment.getIdentifier(), assignment.getRuntimeData());
            }
        });

        pages.forEach(page -> {
            if(page.getRuntimeData().size() > 0){
                result.put(page.getIdentifier(), page.getRuntimeData());
            }
        });

        quizzes.forEach(quiz->{
            if(quiz.getRuntimeData().size() > 0){
                result.put(quiz.getIdentifier(),  quiz.getRuntimeData());
            }
        });

        questions().forEach(question->{
            if(question.getRuntimeData().size()>0){
                result.put(question.getIdentifier(), question.getRuntimeData());
            }
        });


        return  result;
    }

    public CourseResources loadRuntimeData(JsonObject data){
        Iterator<Map.Entry<String,Object>> it = data.stream().iterator();

        while (it.hasNext()){
            Map.Entry<String,Object> curr = it.next();
            String identifier = curr.getKey();
            JsonObject runtimeData = (JsonObject) curr.getValue();

            setRuntimeData(identifier, runtimeData);


        }

        return this;
    }

    private void setRuntimeData(String identifier, JsonObject data){
        if(course.getIdentifier().equals(identifier)){
            course.setRuntimeData(data);
            return;
        }

        for(Assignment assignment: assignments()){
            if(assignment.getIdentifier().equals(identifier)){
                assignment.setRuntimeData(data);
                return;
            }
        }

        for(Quiz quiz: quizzes()){
            if(quiz.getIdentifier().equals(identifier)){
                quiz.setRuntimeData(data);
                return;
            }
        }

        for(QuizQuestion question: questions()){
            if(question.getIdentifier().equals(identifier)){
                question.setRuntimeData(data);
                return;
            }
        }

        for(Page page: pages()){
            if(page.getIdentifier().equals(identifier)){
                page.setRuntimeData(data);
                return;
            }
        }
    }

}
