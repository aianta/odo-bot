package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.operations.*;
import ca.ualberta.odobot.explorer.canvas.resources.*;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.DataGenerationPlan;
import ca.ualberta.odobot.explorer.model.Operation;
import ca.ualberta.odobot.explorer.model.ToDo;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.apache.regexp.RE;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

public class PlanTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PlanTask.class);
    private static final int RESOURCE_LIMIT = 2;

    JsonObject config;

    Promise<JsonObject> promise;

    public PlanTask(JsonObject config, Promise<JsonObject> promise){
        this.config = config;
        this.promise = promise;
    }

    @Override
    public void run() {

        DataGenerationPlan result = new DataGenerationPlan();

        result.setCoursePaths(
                config.getJsonArray(PlanRequestFields.COURSES.field()).stream().map(o->(String)o).collect(Collectors.toList())
        );

        result.setResourceList(
                config.getJsonArray(PlanRequestFields.COURSES.field()).stream()
                        .map(o->(String)o) //Treat all elements as strings, specifically paths to IMSCC files
                        .map(ResourceManager::loadCourse)
                        .collect(Collectors.toList())
        );

        result.setCaseManifest(
                result.getResourceList().stream().map(resources -> computePlan(resources)).collect(ToDo::new, ToDo::addAll, ToDo::addAll)
        );

        promise.complete(result.toJson());

    }

    private static ToDo computePlan(CourseResources resources){

        ToDo manifest = new ToDo();

        //First have to create the course
        CourseOperations courseOperations = new CourseOperations(resources.getCourse());

        Operation createCourse = new Operation(Operation.OperationType.CREATE, Course.class);
        createCourse.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
        createCourse.setExecuteMethod(courseOperations::create);

        Operation deleteCourse = new Operation(Operation.OperationType.DELETE, Course.class);
        deleteCourse.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
        deleteCourse.setExecuteMethod(courseOperations::delete);

        manifest.add(createCourse);

        //All subsequent operations depend on this operation

        //Create module operations
        resources.modules().stream().limit(RESOURCE_LIMIT).forEach(module->{

            ModuleOperations moduleOperations = new ModuleOperations(resources.getCourse(), module);

            Operation createOp = new Operation(Operation.OperationType.CREATE, Module.class);
            //Associate related resources to operation via their IMSCC identifiers
            createOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            createOp.addRelatedIdentifier("module", module.getIdentifier());
            createOp.addDependency(createCourse); //Create module operation depends on course being created.
            createOp.setExecuteMethod(moduleOperations::create);

            Operation editOp = new Operation(Operation.OperationType.EDIT, Module.class);
            //Associate related resources to operation via their IMSCC identifiers
            editOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            editOp.addRelatedIdentifier("module", module.getIdentifier());
            editOp.addDependency(createCourse).addDependency(createOp); //Edit module operation depends on course being created and module being created.
            editOp.setExecuteMethod(moduleOperations::edit);

            Operation deleteOp = new Operation(Operation.OperationType.DELETE, Module.class);
            //Associate related resources to operation via their IMSCC identifiers
            deleteOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            deleteOp.addRelatedIdentifier("module", module.getIdentifier());
            deleteOp.addDependency(createCourse).addDependency(createOp).addDependency(editOp); //Delete module operation depends on course being created, module being created, and module being edited.
            deleteOp.setExecuteMethod(moduleOperations::delete);

            manifest.add(createOp);
            manifest.add(editOp);
            //TODO: For candidacy evaluation plan modules do not get deleted
            //manifest.add(deleteOp);
        });

        //Create Assignment operations
        resources.assignments().stream().limit(RESOURCE_LIMIT).forEach(assignment->{

            AssignmentOperations assignmentOperations = new AssignmentOperations(resources.getCourse(), assignment);

            Operation createOp = new Operation(Operation.OperationType.CREATE, Assignment.class);
            createOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            createOp.addRelatedIdentifier("assignment", assignment.getIdentifier());
            createOp.addDependency(createCourse);
            createOp.setExecuteMethod(assignmentOperations::create);

            Operation editOp = new Operation(Operation.OperationType.EDIT, Assignment.class);
            editOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            editOp.addRelatedIdentifier("assignment", assignment.getIdentifier());
            editOp.addDependency(createCourse, createOp);
            editOp.setExecuteMethod(assignmentOperations::edit);

            Operation deleteOp = new Operation(Operation.OperationType.DELETE, Assignment.class);
            deleteOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            deleteOp.addRelatedIdentifier("assignment", assignment.getIdentifier());
            deleteOp.addDependency(createOp, createOp, editOp);
            deleteOp.setExecuteMethod(assignmentOperations::delete);

            manifest.add(createOp);
            manifest.add(editOp);
            manifest.add(deleteOp);

        });

        //Create quiz operations
        resources.quizzes().stream().limit(RESOURCE_LIMIT).forEach(quiz->{

            QuizOperations quizOperations = new QuizOperations(resources.getCourse(),quiz);

            Operation createOp = new Operation(Operation.OperationType.CREATE, Quiz.class);
            createOp.addRelatedIdentifier("quiz", quiz.getIdentifier());
            createOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            createOp.addDependency(createCourse);
            createOp.setExecuteMethod(quizOperations::create);

            Operation editOp = new Operation(Operation.OperationType.EDIT, Quiz.class);
            editOp.addRelatedIdentifier("quiz", quiz.getIdentifier());
            editOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            editOp.addDependency(createCourse, createOp);
            editOp.setExecuteMethod(quizOperations::edit);

            Operation deleteOp = new Operation(Operation.OperationType.DELETE, Quiz.class);
            deleteOp.addRelatedIdentifier("quiz", quiz.getIdentifier());
            deleteOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            deleteOp.addDependency(createCourse, createOp, editOp);
            deleteOp.setExecuteMethod(quizOperations::delete);

            manifest.add(createOp);
            manifest.add(editOp);

            //TODO: Commented out because for candidacy evaluation plan we do not work with quiz questions.
            //Create the operations for the questions of this quiz as well
//            resources.getQuizQuestions(quiz).stream().limit(RESOURCE_LIMIT).forEach(
//                    question -> {
//
//                        QuizQuestionOperations quizQuestionOperations = new QuizQuestionOperations(resources.getCourse(), quiz, question);
//
//                        Operation createQuestion = new Operation(Operation.OperationType.CREATE, QuizQuestion.class);
//                        createQuestion.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
//                        createQuestion.addRelatedIdentifier("quiz", quiz.getIdentifier());
//                        createQuestion.addRelatedIdentifier("question", question.getIdentifier());
//                        createQuestion.addDependency(createCourse, createOp);
//                        createQuestion.setExecuteMethod(quizQuestionOperations::create);
//
//                        Operation editQuestion = new Operation(Operation.OperationType.EDIT, QuizQuestion.class);
//                        editQuestion.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
//                        editQuestion.addRelatedIdentifier("quiz", quiz.getIdentifier());
//                        editQuestion.addRelatedIdentifier("question", question.getIdentifier());
//                        editQuestion.addDependency(createCourse, createOp, createQuestion);
//                        editQuestion.setExecuteMethod(quizQuestionOperations::edit);
//
//                        Operation deleteQuestion = new Operation(Operation.OperationType.DELETE, QuizQuestion.class);
//                        deleteQuestion.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
//                        deleteQuestion.addRelatedIdentifier("quiz", quiz.getIdentifier());
//                        deleteQuestion.addRelatedIdentifier("question", question.getIdentifier());
//                        deleteQuestion.addDependency(createCourse, createOp, editQuestion);
//
//                        manifest.add(createQuestion);
//                        manifest.add(editQuestion);
//                        manifest.add(deleteQuestion);
//
//                        //Make the delete operation for the quiz depend on the operations for all questions in the quiz. Ensuring we do not delete the quiz before we're done manipulating the questions.
//                        deleteOp.addDependency(createQuestion, editQuestion, deleteQuestion);
//                    }
//            );

            //TODO: For candidacy evaluation plan quizzes do not get deleted.
            //manifest.add(deleteOp);

        });

        //Create page operations
        resources.pages().stream().limit(RESOURCE_LIMIT).forEach(page->{

            PageOperations pageOperations = new PageOperations(resources.getCourse(), page);

            Operation createOp = new Operation(Operation.OperationType.CREATE, Page.class);
            createOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            createOp.addRelatedIdentifier("page", page.getIdentifier());
            createOp.addDependency(createCourse);
            createOp.setExecuteMethod(pageOperations::create);

            Operation editOp = new Operation(Operation.OperationType.EDIT, Page.class);
            editOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            editOp.addRelatedIdentifier("page", page.getIdentifier());
            editOp.addDependency(createCourse, createOp);
            editOp.setExecuteMethod(pageOperations::edit);

            Operation deleteOp = new Operation(Operation.OperationType.DELETE, Page.class);
            deleteOp.addRelatedIdentifier("course", resources.getCourse().getIdentifier());
            deleteOp.addRelatedIdentifier("page", page.getIdentifier());
            deleteOp.addDependency(createCourse, createOp, editOp);
            deleteOp.setExecuteMethod(pageOperations::delete);

            manifest.add(createOp);
            manifest.add(editOp);
            manifest.add(deleteOp);


        });

        deleteCourse.addDependency(manifest);
        manifest.add(deleteCourse);

        return manifest;
    }


}
