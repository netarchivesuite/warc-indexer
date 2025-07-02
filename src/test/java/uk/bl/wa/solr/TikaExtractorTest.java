/**
 * 
 */
package uk.bl.wa.solr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import uk.bl.wa.analyser.payload.TikaPayloadAnalyser;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class TikaExtractorTest {
    private static Logger log = LoggerFactory.getLogger(TikaExtractorTest.class);

    private TikaPayloadAnalyser tika;

    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
        tika = new TikaPayloadAnalyser();
        tika.configure(ConfigFactory.load());
    }

    @Test
    public void testMonaLisa() throws Exception {
        File ml = new File("src/test/resources/wikipedia-mona-lisa/Mona_Lisa.html");
        if (!ml.exists()) {
            log.error("The Mona Lisa test file '" + ml + "' does not exist");
            return;
        }
        URL url = ml.toURI().toURL();
        SolrRecord solr = SolrRecordFactory.createFactory(null).createRecord();
        tika.extract(ml.getPath(), solr, url.openStream(), url.toString());
        System.out.println("SOLR " + solr.getSolrDocument().toString());
        String text = (String) solr.getField(SolrFields.SOLR_EXTRACTED_TEXT)
                .getValue();
        assertTrue("Text should contain this string!",
                text.contains("Mona Lisa"));
        assertFalse(
                "Text should NOT contain this string! (implies bad newline handling)",
                text.contains("encyclopediaMona"));
    }

}
