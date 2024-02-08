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

    public CourseResources quizQuestions(QuizQuestion question){
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


}
