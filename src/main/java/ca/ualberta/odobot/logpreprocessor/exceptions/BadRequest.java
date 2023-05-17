package ca.ualberta.odobot.logpreprocessor.exceptions;

public class BadRequest extends RuntimeException{

    String msg;

    public BadRequest(String msg){
         this.msg = msg;
    }

    public String getMessage(){
        return msg;
    }

}
