package uk.bl.wa.analyser.payload;

import java.io.File;

import uk.bl.wa.droidlight.DetectionResult;
import uk.bl.wa.droidlight.DroidSignatureVerifier;

public class DroidVerifierHeuristicTest {
    
    public static void main(String[] args) throws Exception{
        
 
        String sigFile="/home/teg/workspace/warc-indexer/src/main/resources/DROID_SignatureFile_V124.xml";
        DroidSignatureVerifier dd2= new DroidSignatureVerifier(new File(sigFile));
        
        DetectionResult[] detect = dd2.detect(new File("/home/teg/Downloads/quiz-x_light-reconstructed.svg"));
        System.out.println(detect[0]);
        
    }

}
