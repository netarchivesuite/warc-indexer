package uk.bl.wa.opensearch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpensearchUrlTest {
    @Test
    public void testInvalidUrls() {
    	OpensearchUrl eu;
    	
    	eu = new OpensearchUrl(null);
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("invalid");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("ftp://server");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("http://");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("https://");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("http://server");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("https://server");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("http://server");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("https://server/");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("http://server:x");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("https://server:x");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("http://server:");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("https://server:");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("http://server:9200");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("https://server:9200");
    	assertFalse(eu.isValid());

    	eu = new OpensearchUrl("http://server:9200index");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("https://server:9200index");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("http://server:9200/");
    	assertFalse(eu.isValid());
    	
    	eu = new OpensearchUrl("https://server:9200/");
    	assertFalse(eu.isValid());
    }
    
    @Test
    public void testValidUrls() {
    	OpensearchUrl eu;
    	
    	eu = new OpensearchUrl("http://server:9200/index");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTP);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 9200);
    	assertEquals(eu.getIndexName(), "index");
    	
    	eu = new OpensearchUrl("https://server:9200/index");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTPS);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 9200);
    	assertEquals(eu.getIndexName(), "index");

    	eu = new OpensearchUrl("http://server/index");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTP);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 80);
    	assertEquals(eu.getIndexName(), "index");
    	
    	eu = new OpensearchUrl("https://server/index");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTPS);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 80);
    	assertEquals(eu.getIndexName(), "index");

    	eu = new OpensearchUrl("https://server:9200/index/");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTPS);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 9200);
    	assertEquals(eu.getIndexName(), "index");

    	eu = new OpensearchUrl("http://server:9200/index/");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTP);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 9200);
    	assertEquals(eu.getIndexName(), "index");
    	
    	eu = new OpensearchUrl("https://server/index/");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTPS);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 80);
    	assertEquals(eu.getIndexName(), "index");
    	
    	eu = new OpensearchUrl("http://server/index/");
    	assertTrue(eu.isValid());
    	assertEquals(eu.getScheme(), OpensearchUrl.HTTP);
    	assertEquals(eu.getServer(), "server");
    	assertEquals(eu.getPort(), 80);
    	assertEquals(eu.getIndexName(), "index");
    }
}
