/**
 * 
 */
package uk.bl.wa.annotation;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class AnnotationsTest {

    private static final String ML_ANNOTATIONS = "annotations/mona-lisa-annotations.json";
    private static final String ML_OASURTS = "annotations/openAccessSurts.txt";

    public static final String ML_ANNOTATIONS_PATH = AnnotationsTest.class
            .getClassLoader().getResource(AnnotationsTest.ML_ANNOTATIONS)
            .getPath();
    public static final String ML_OASURTS_PATH = AnnotationsTest.class
            .getClassLoader()
            .getResource(AnnotationsTest.ML_OASURTS).getPath();

    @Test
    public void testJsonSerialisation() throws JsonParseException,
            JsonMappingException, IOException {
        //
        Annotations ann = new Annotations();
        // canon.urlStringToKey(uri)
        ann.getCollections()
                .get("resource")
                .put("http://en.wikipedia.org/wiki/Mona_Lisa",
                        new UriCollection("Wikipedia", new String[] {
                                "Wikipedia", "Wikipedia|Main Site",
                                "Wikipedia|Main Site|Mona Lisa" },
                                new String[] { "Crowdsourcing" }));
        //
        ann.getCollections()
                .get("root")
                .put("http://en.wikipedia.org/",
                        new UriCollection("Wikipedia", new String[] {
                                "Wikipedia", "Wikipedia|Main Site" },
                                new String[] { "Crowdsourcing" }));
        //
        ann.getCollections()
                .get("subdomains")
                .put("en.wikipedia.org",
                        new UriCollection("Wikipedia",
                                new String[] { "Wikipedia" },
                                new String[] { "Crowdsourcing" }));
        //
        ann.getCollections()
                .get("source_file_matches")
                .put("flashfrozen-",
                        new UriCollection("Wikipedia", new String[] {
                                "Wikipedia", "Wikipedia|Main Site" },
                                new String[] { "Crowdsourcing" }));
        // Date ranges:
        ann.getCollectionDateRanges().put("Wikipedia",
                new DateRange(null, null));
        ann.getCollectionDateRanges().put("Wikipedia|Main Site",
                new DateRange(null, null));
        ann.getCollectionDateRanges().put("Wikipedia|Main Site|Mona Lisa",
                new DateRange(null, null));
        String json = ann.toJson();
        Annotations ann2 = Annotations.fromJson(json);
        String filePath = this.getClass().getClassLoader()
                .getResource(ML_ANNOTATIONS).getPath();
        ann2.toJsonFile(filePath);
        String json2 = ann2.toJson();
        // Having performed a full Json-Java-Json cycle, check the Json is the
        // same:
        assertEquals(
                "The test Json-Java-Json serialisation cycle was not lossless",
                json, json2);
    }

}
