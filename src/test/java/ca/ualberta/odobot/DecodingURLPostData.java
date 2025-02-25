package ca.ualberta.odobot;


import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class DecodingURLPostData {

    private static final Logger log = LoggerFactory.getLogger(DecodingURLPostData.class);

    @Test
    public void test(){

        String sample = "_method=POST&utf8=%E2%9C%93&authenticity_token=bUsS3ah6c2U%2BYeZx3G1TxRpzBi468KOm1JNiRapjg8YUJCSn7Ew4MmYP1CW3WxGuMSR1eAym59KT4DMSnDTtvw%3D%3D&name=Introduction+to+Psychology&context_module%5Bname%5D=Introduction+to+Psychology&unlock_at=&context_module%5Bunlock_at%5D=&requirement_count=&context_module%5Brequirement_count%5D=&require_sequential_progress=0&context_module%5Brequire_sequential_progress%5D=0&context_module%5Bprerequisites%5D=&context_module%5Bcompletion_requirements%5D%5Bnone%5D=none";

        try{
            String decoded = URLDecoder.decode(sample, StandardCharsets.UTF_8.name());
            String [] split = decoded.split("&");
            String target = Arrays.stream(split).filter(s->s.contains("context_module[name]")).findFirst().get();
            target = target.split("=")[1];
            log.info(target);

            log.info(decoded);


        }catch (UnsupportedEncodingException e){
            log.error(e.getMessage(),e);
        }
    }
}
