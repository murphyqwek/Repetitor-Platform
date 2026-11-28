package tutor.assignmentservice.bank;

public enum AnswerType {
    TEXT, NUMBER, SINGLE_CHOICE, MULTIPLE_CHOICE, MATCHING, FILES;

    public String checkMode() {
        return this == FILES ? "MANUAL" : "AUTOMATIC";
    }
}
