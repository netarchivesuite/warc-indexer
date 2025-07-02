package uk.bl.wa.annotation;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.apache.commons.httpclient.URIException;
import org.apache.solr.common.SolrInputDocument;
import org.archive.util.SurtPrefixSet;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import uk.bl.wa.indexer.WARCIndexer;
import uk.bl.wa.solr.SolrFields;

public class AnnotatorTest {

    private Annotator annotator;

    public static Annotator getTestAnnotator()
            throws JsonParseException, JsonMappingException, IOException {
        Annotations annotations = Annotations
                .fromJsonFile(AnnotationsTest.ML_ANNOTATIONS_PATH);
        SurtPrefixSet oaSurts = Annotator
                .loadSurtPrefix(AnnotationsTest.ML_OASURTS_PATH);
        Annotator annotator = new Annotator(annotations, oaSurts);
        return annotator;
    }

    @Before
    public void setUp() throws Exception {
        this.annotator = getTestAnnotator();
    }

    @Test
    public void testApplyAnnotations() throws URIException, URISyntaxException {
        innerTestApplyAnnotations("http://en.wikipedia.org/wiki/Mona_Lisa", "", 4);
        innerTestApplyAnnotations("http://en.wikipedia.org/", "", 3);
        innerTestApplyAnnotations("http://www.wikipedia.org/", "", 2);
        innerTestApplyAnnotations("http://www.wikipedia.org/", "flashfrozen-jwat-recompressed.warc.gz", 3);
    }

    private void innerTestApplyAnnotations(String uriString, String sourceFile, int expected)
            throws URIException, URISyntaxException {
        //
        URI uri = URI.create(uriString);
        //
        // Get the calendar instance.
        Calendar calendar = Calendar.getInstance();
        // Set the time for the notification to occur.
        calendar.set(Calendar.YEAR, 2013);
        calendar.set(Calendar.MONTH, 6);
        calendar.set(Calendar.DAY_OF_MONTH, 17);
        calendar.set(Calendar.HOUR_OF_DAY, 10);
        calendar.set(Calendar.MINUTE, 45);
        calendar.set(Calendar.SECOND, 0);
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        //
        SolrInputDocument solr = new SolrInputDocument();
        Date d = calendar.getTime();
        solr.setField(SolrFields.CRAWL_DATE, WARCIndexer.formatter.format(d));
        solr.setField(SolrFields.SOLR_URL, uri);
        solr.setField(SolrFields.SOURCE_FILE, sourceFile);
        //
        annotator.applyAnnotations(uri, solr);
        Annotator.prettyPrint(System.out, solr);
        int found = 0;
        //
        for (Object item : solr.getFieldValues(SolrFields.SOLR_COLLECTIONS)) {
            System.out.println("Contains... " + item);
                if ("Wikipedia".equals(item))
                    found++;
                if ("Wikipedia|Main Site".equals(item))
                    found++;
                if ("Wikipedia|Main Site|Mona Lisa".equals(item))
                    found++;
                
        }
        if (solr.containsKey(SolrFields.ACCESS_TERMS)) {
            for (Object item : solr
                    .getFieldValues(SolrFields.ACCESS_TERMS)) {
                    if ("OA".equals(item))
                        found++;

            }
        }
        assertTrue("Can't find the " + expected
                + " expected entries in 'collections for " + uriString
                + ", got " + found,
                found == expected);
    }

}
