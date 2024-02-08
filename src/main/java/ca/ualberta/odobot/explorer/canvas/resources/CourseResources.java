package ca.ualberta.odobot.explorer.canvas.resources;

import java.util.ArrayList;
import java.util.List;

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

    public List<Quiz> quizzes(){
        return quizzes;
    }
}
