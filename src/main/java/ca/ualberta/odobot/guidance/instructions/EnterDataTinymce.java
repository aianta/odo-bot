package ca.ualberta.odobot.guidance.instructions;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class EnterDataTinymce extends EnterData{

    public String editorId;

    public boolean equals(Object o){

        if(!(o instanceof EnterDataTinymce)){
            return false;
        }

        EnterDataTinymce other = (EnterDataTinymce)o;
        return xpath.equals(other.xpath) && data.equals(other.data) && editorId.equals(other.editorId);

    }

    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(73, 97);
        builder.append(xpath);
        builder.append(data);
        builder.append(editorId);
        builder.append(parameterId);
        return builder.toHashCode();
    }

}
