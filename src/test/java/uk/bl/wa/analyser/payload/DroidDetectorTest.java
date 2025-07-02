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

import uk.bl.wa.nanite.droid.DroidDetector;
import uk.gov.nationalarchives.droid.core.SignatureParseException;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class DroidDetectorTest {

    private DroidDetector dd;

    /**
     * @throws SignatureParseException 
     * @throws IOException 
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws IOException, SignatureParseException {
        dd = new DroidDetector();

    }

    /**
     * 
     * @throws IOException
     * @throws CommandExecutionException
     * @throws URISyntaxException
     */
    @Test
    public void testBasicDetection() throws IOException,
            URISyntaxException {
        this.runDroids("cc.png", "image/png");
        this.runDroids("cc0.mp3", "audio/mpeg");
    }

    private void runDroids(String filename, String expected) throws IOException,
            URISyntaxException {

        // Set up File and Metadata:
        String filePath = this.getClass().getClassLoader().getResource(filename)
                .getPath();
        File file = new File(filePath);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());

        // Test identification two ways:
        assertEquals("ID of " + filename + " as File, failed.", expected, dd
                .detect(file).getBaseType().toString());

        assertEquals("ID of " + filename + " as InputStream, failed.",
                expected, dd.detect(new FileInputStream(file), metadata)
                        .getBaseType().toString());

    }
}
