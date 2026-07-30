package uk.bl.wa.analyser;

import java.io.FileInputStream;
import java.io.InputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;

import uk.bl.wa.nanite.droid.DroidDetector;


public class DroidBugHunt{
    public static void main(String[] args) throws Exception {
        DroidDetector dd = new DroidDetector();

        InputStream in = new FileInputStream("/home/teg/Downloads/image.webp");
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "image.webp");
        
        System.out.println("Calling dd.detect()...");
        MediaType mt = dd.detect(in, metadata);
        System.out.println("Result: " + mt);
    }
}
