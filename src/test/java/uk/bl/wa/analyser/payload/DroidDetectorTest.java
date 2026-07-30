/**
 * 
 */
package uk.bl.wa.analyser.payload;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.Before;
import org.junit.Test;

import uk.bl.wa.droidlight.DroidSignatureVerifierHeuristic;
/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class DroidDetectorTest {

    private DroidSignatureVerifierHeuristic dd;

    /**
     * @throws SignatureParseException 
     * @throws IOException 
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
        //TODO read from container
        String filePath="/home/teg/eclipse-workspace/droid-light/src/main/resources/DROID_SignatureFile_V124.xml";
        File file=new File(filePath);
        dd = new DroidSignatureVerifierHeuristic(file);

    }

    /**
     * 
     * @throws IOException
     * @throws CommandExecutionException
     * @throws URISyntaxException
     */
    @Test
    public void testBasicDetection() throws Exception{
        //Values here can change in next version of SignatureFile
        this.runDroids("cc.png", "fmt/11  Portable Network Graphics  [image/png; version=1.0]");
        this.runDroids("cc0.mp3", "fmt/134  MPEG 1/2 Audio Layer 3  [audio/mpeg]");
    }

    private void runDroids(String filename, String expected) throws Exception{

        // Set up File and Metadata:
        String filePath = this.getClass().getClassLoader().getResource(filename)
                .getPath();
        File file = new File(filePath);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());

        // Test identification two ways:
        assertEquals("ID of " + filename + " as File, failed.", expected, dd.detect(file,filename)[0].toString());

        assertEquals("ID of " + filename + " as InputStream, failed.",
                expected, dd.detect(new FileInputStream(file), filename)[0].toString());
                        

    }
}
