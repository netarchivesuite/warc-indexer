package uk.bl.wa.solr;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.impl.ConfigImpl;
import org.junit.Test;

import static org.junit.Assert.*;
import com.typesafe.config.Config;

import java.io.File;
import java.net.URL;

public class SolrRecordFactoryTest {

    @Test
    public void testBasicFactorySetup() {
        final String KEY_URL_MAX_LENGTH = "warc.solr.field_setup.fields.url.max_length";
        final String KEY_URL_NORM = "warc.solr.field_setup.fields.url_norm.rewrites";

        URL ref = Thread.currentThread().getContextClassLoader().getResource("reference.conf");
        assertNotNull("The config reference.conf should exist", ref);
        File configFilePath = new File(ref.getFile());
        Config conf = ConfigFactory.parseFile(configFilePath);
        assertTrue("Max for url field should be specified with key " + KEY_URL_MAX_LENGTH,
                   conf.hasPath(KEY_URL_MAX_LENGTH));
        assertTrue("There should be a setup for url_norm.rewrites", conf.hasPath(KEY_URL_NORM));
        SolrRecordFactory factory = SolrRecordFactory.createFactory(conf);

        // Check max_length handling
        {
            SolrRecord record = factory.createRecord();
            final String FAKE_URL = "short";
            record.addField(SolrFields.SOLR_URL, FAKE_URL);
            assertEquals("The length of the url field with a short String should be unchanged",
                         FAKE_URL.length(), record.getFieldValue(SolrFields.SOLR_URL).toString().length());
        }
        {
            SolrRecord record = factory.createRecord();
            StringBuilder fakeURL = new StringBuilder(4000);
            fakeURL.append("short");
            for (int i = 0 ; i < 2500 ; i++) {
                fakeURL.append("O");
            }
            record.addField(SolrFields.SOLR_URL, fakeURL.toString());
            assertEquals("The length of the url field with a huge String should be trimmed",
                         conf.getBytes(KEY_URL_MAX_LENGTH).intValue(),
                         record.getFieldValue(SolrFields.SOLR_URL).toString().length());
        }

        // Check whitespace collapsing
        {
            SolrRecord record = factory.createRecord();
            record.addField(SolrFields.SOLR_EXTRACTED_TEXT, " leading   middle   and   trailing spaces  ");
            assertEquals("Multiple consecutive white spaces should be collapsed",
                         "leading middle and trailing spaces",
                         record.getFieldValue(SolrFields.SOLR_EXTRACTED_TEXT).toString());
        }

        // Check url_norm rewrite
        final String BASE_URL = "http://example.com/foo.png";
        final String PROBLEM_URL = "http://example.com/foo.png%201080w";
        {
            SolrRecord record = factory.createRecord();
            record.addField(SolrFields.SOLR_URL_NORMALISED, BASE_URL);
            assertEquals("Non-problematic URL should be stored unchanged in url_norm",
                         BASE_URL,
                         record.getFieldValue(SolrFields.SOLR_URL_NORMALISED).toString());
        }
        {
            SolrRecord record = factory.createRecord();
            record.addField(SolrFields.SOLR_URL_NORMALISED, PROBLEM_URL);
            assertEquals("Problematic URL should be adjusted to base URL in url_norm",
                         BASE_URL,
                         record.getFieldValue(SolrFields.SOLR_URL_NORMALISED).toString());
        }

    }
    

    @Test
    public void testFieldAdjuster() {
        URL ref = Thread.currentThread().getContextClassLoader().getResource("reference.conf");
        assertNotNull("The config reference.conf should exist", ref);
        File configFilePath = new File(ref.getFile());
        Config conf = ConfigFactory.parseFile(configFilePath);                
        
        SolrRecordFactory factory = SolrRecordFactory.createFactory(conf);        
        String field="content_text";
        String value;
        String result;
        
        value="Very simple text";
        result = factory.applyAdjustment(field, value);
        assertEquals(value,result);
        
        value="Lots\rof\ncontrol characters"; //both has \n (new line) and \r ( carriage return)
        result = factory.applyAdjustment(field, value);
        assertEquals("Lots of control characters",result);
        
        value="Some$special#characters"; //these are not removed
        result = factory.applyAdjustment(field, value);
        assertEquals("Some$special#characters",result);
        
        
    }
    
    
}
