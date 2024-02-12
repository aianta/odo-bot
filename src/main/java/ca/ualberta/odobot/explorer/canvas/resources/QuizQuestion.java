package ca.ualberta.odobot.explorer.canvas.resources;

public class QuizQuestion extends BaseResource{

    private String name;

    private int id;

    private String relatedQuizIdentifier;

    public String getRelatedQuizIdentifier() {
        return relatedQuizIdentifier;
    }

    public void setRelatedQuizIdentifier(String relatedQuizIdentifier) {
        this.relatedQuizIdentifier = relatedQuizIdentifier;
    }

    /**
     * The type of the question used as values for the question type input.
     */
    private QuestionType type;

    public enum QuestionType{
        MULTIPLE_CHOICE("multiple_choice_question"),
        TRUE_FALSE("true_false_question"),
        ESSAY("essay_question")
        ;

        public String optionValue;

        QuestionType(String value){
            this.optionValue = value;
        }
    }

    private String body;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public QuestionType getType() {
        return type;
    }

    public void setType(QuestionType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
