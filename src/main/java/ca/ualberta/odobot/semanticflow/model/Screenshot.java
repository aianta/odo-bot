package ca.ualberta.odobot.semanticflow.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

public class Screenshot {

    private static final Logger log = LoggerFactory.getLogger(Screenshot.class);

    private BufferedImage image;

    public Screenshot(String base64){
        byte [] imageBytes = Base64.getDecoder().decode(base64);
        try(ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
            image = ImageIO.read(bais);
        } catch (IOException e) {
            log.error("Error while reading screenshot image", e);
        }
    }

    /**
     * @param base64 a base64 encoded image, or null, e.g. when OdoX could not capture the tab.
     * @return the screenshot, or null if there is no image.
     */
    public static Screenshot fromBase64(String base64){
        return base64 == null ? null : new Screenshot(base64);
    }

    public BufferedImage crop(BoundingBox boundingBox){
        if(image==null){
            log.error("Cannot crop because image is null");
            return null;
        }
        BufferedImage croppedImage = image.getSubimage(
                boundingBox.getX(),
                boundingBox.getY(),
                boundingBox.getWidth(),
                boundingBox.getHeight()
        );
        return croppedImage;
    }

    public BufferedImage getImage(){
        return image;
    }

    public byte[] asBytes(){
        if(image==null){
            log.error("Cannot get image as bytes because image is null");
            return null;
        }

        try(ByteArrayOutputStream bos = new ByteArrayOutputStream()){
            ImageIO.write(image, "png", bos);
            bos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public File saveToPath(Path path){
        return saveToPath(image, path);
    }

    public static File saveToPath(BufferedImage tgtImage, Path path){
        if(tgtImage==null){
            log.error("Cannot save image to {} because image is null", path.toString());
            return null;
        }
        try{
            File fout = new File(path.toString());
            ImageIO.write(tgtImage, "png", fout);
            return fout;
        } catch (IOException e) {
            log.error("Error while saving screenshot!", e);
            return null;
        }
    }
}
