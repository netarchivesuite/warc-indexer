package uk.bl.wa.util;

import static org.junit.Assert.assertEquals;

import org.apache.commons.httpclient.URIException;
import org.junit.Test;

public class NormalisationTest {

    @Test
    public void testWARCHeaderValueSanitise() {
        String[][] TESTS = new String[][]{
                // input, expected
                {"foo bar", "foo bar"},
                {"<foo bar", "<foo bar"},
                {"foo bar>", "foo bar>"},
                {"<foo bar>", "foo bar"},
                {"foo< >bar", "foo< >bar"},
                {"<foo< >bar>", "foo< >bar"}
        };
        for (String[] test: TESTS) {
            assertEquals("WARC-header value should be sanitised as expected for input '" + test[0] + "'",
                         test[1], Normalisation.sanitiseWARCHeaderValue(test[0]));
        }

    }

    @Test
    public void testEncodedTrailingSlash() {
        String[][] TESTS = new String[][]{
                {
                        "https://www.example.com/foo?param=https://www.example.com/other/",
                        "http://example.com/foo?param=https://www.example.com/other"
                },
                {
                        "https://www.example.com/foo?param=https:%2F%2Fwww.example.com%2Fother%2F",
                        "http://example.com/foo?param=https://www.example.com/other"
                }
        };
        for (String[] test: TESTS) {
            assertEquals("Default normalisation should yield the expected result for " + test[0],
                         test[1], Normalisation.canonicaliseURL(test[0]));
        }
    }

    @Test
    public void restResolveRelative() {
        String[][] TESTS = new String[][]{
                // root, relative, expected
                {"http://example.com/",             "foo.html",      "http://example.com/foo.html", "true"},
                {"http://example.com/bar/",         "zoo/baz.html",  "http://example.com/bar/zoo/baz.html", "true"},
                {"http://example.com/bar",          "/zoo/baz.html", "http://example.com/zoo/baz.html", "true"},
                {"http://example.com/bar/zoo",      "/",             "http://example.com/", "true"},
                {"http://example.com/",             "http://other.example.com", "http://other.example.com/", "true"},
                {"http://example.com/",             "", "http://example.com/", "true"},
                {"http://example.com/",             "", "http://example.com/", "true"},
                {"http://example.com/foo|bar.html", "/top/", "http://example.com/top/", "false"},
                {"http://example.com/foo | bar/",   "sub/", "http://example.com/foo%20|%20bar/sub", "true"},
                {"http://example.com/foo | bar/",   "sub/", "http://example.com/foo | bar/sub/", "false"},
                {"http://example.com/faulty%g/gg",  "sub", "http://example.com/faulty%25g/sub", "true"},
                {"http://example.com/faulty%g/gg",  "sub", "http://example.com/faulty%g/sub", "false"},
                {"http://www.example.com/faulty%g/gg",  "sub", "http://example.com/faulty%25g/sub", "true"},
                {"http://www.example.com/faulty%g/gg",  "sub", "http://www.example.com/faulty%g/sub", "false"},
        };
        for (String[] test: TESTS) {
            assertEquals("rel('" + test[0] + "', '" + test[1] + "', " + test[3] + ") should give the expected result",
                         test[2], Normalisation.resolveRelative(test[0], test[1], Boolean.parseBoolean(test[3])));
        }
    }

    // The canonicalizer from archive.org removes www if the URL has a path part and not if it is domain only
    @Test
    public void testWWWRemoveOnNormalisation() {
        String SOURCE =   "http://www.example.com/";
        String EXPECTED = "http://example.com/";
        assertEquals("The input '" + SOURCE + "' should be normalised unambiguously as expected",
                     EXPECTED, Normalisation.canonicaliseURL(SOURCE, true, true));
    }

    @Test
    public void testURLNormalisation() {
        final String[][] TESTS = new String[][]{
                // input, ambiguous, unambiguous
                {"http://example.com",  "http://example.com/", "http://example.com/"},
                {"http://example.com/", "http://example.com/", "http://example.com/"},
                {"https://example.com", "http://example.com/", "http://example.com/"},
                {"https://example.com", "http://example.com/", "http://example.com/"},
                {"http://www.example.com",  "http://www.example.com/", "http://example.com/"},
                {"https://www.example.com", "http://www.example.com/", "http://example.com/"},
                {"https://ww2.example.com", "http://ww2.example.com/", "http://example.com/"},
                {"https://www8.example.com", "http://www8.example.com/", "http://example.com/"},
                {"http://ww2.example.com",  "http://ww2.example.com/", "http://example.com/"},
                {"/foo",                "/foo",                "/foo"},
                {"/foo/",               "/foo",                "/foo"},
                {"/%2A",                "/%2a",                "/*"},
                {"/%2a",                "/%2a",                "/*"},
                {"/%2a*",               "/%2a*",                "/**"},
                {"/æblegrød",           "/æblegrød",           "/æblegrød"},
                {"%C3%A6blegr%C3%B8d",  "æblegrød",            "æblegrød"},
                {"/æblegrød og øl",     "/æblegrød%20og%20øl", "/æblegrød%20og%20øl"},
                {"Red, Rosé 14%",       "red,%20rosé%2014%25", "red,%20rosé%2014%25"},
                {"Red%2C%20Ros%C3%A9 14%25", "red%2c%20rosé%2014%25",  "red,%20rosé%2014%25"},
                {"/backslash\\",        "/backslash%5c",       "/backslash%5c"},
                {"/backslash%5C",       "/backslash%5c",       "/backslash%5c"},
        };

        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be normalised ambiguously as expected",
                         test[1], Normalisation.canonicaliseURL(test[0], true, false));
            assertEquals("The input '" + test[0] + "' should be normalised unambiguously as expected",
                         test[2], Normalisation.canonicaliseURL(test[0], true, true));
        }
    }

    @Test
    public void testFaultyHighOrderNormalisation() {
        final String[][] TESTS = new String[][]{
                {"Red, Rosé 14%",            "red,%20ros%c3%a9%2014%25", "red,%20rosé%2014%25"},
                {"red,%20ros%c3%a9%2014%25", "red,%20ros%c3%a9%2014%25", "red,%20rosé%2014%25"}
        };

        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be normalised with high-order escaping as expected",
                         test[1], Normalisation.canonicaliseURL(test[0], false, true));
            assertEquals("The input '" + test[0] + "' should be normalised without high-order escaping as expected",
                         test[2], Normalisation.canonicaliseURL(test[0], true, true));
        }
    }

    @Test
    public void testNonUTF8Escapes() {
        final String[][] TESTS = new String[][]{
                {"http://example.com/%C3%86blegr%C3%B8d", "http://example.com/Æblegrød"},     // UTF-8 escapes
                {"http://example.com/%C3%86blegr%C3",     "http://example.com/Æblegr%c3"},    // Half UTF-8 2-byte escape
                {"http://example.com/Æblegrød",           "http://example.com/æblegrød"},     // Direct unicode
                {"http://example.com/%C6blegr%F8d",       "http://example.com/%c6blegr%f8d"}, // ISO-8859-1
                {"http://www.example.com/%C6blegr%F8d",   "http://example.com/%c6blegr%f8d"}, // ISO-8859-1
        };

        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be normalised as expected",
                         test[1], Normalisation.canonicaliseURL(test[0]));
        }
    }

    @Test
    public void testEscapeFix() {
        final String[][] TESTS = new String[][]{
                {"http://example.com/%",         "http://example.com/%25"},
                {"http://example.com/%%25",      "http://example.com/%25%25"},
                {"http://example.com/10% proof", "http://example.com/10%25%20proof"},
                {"http://example.com/%a%2A",     "http://example.com/%25a%2a"},
                {"http://example.com/%g1%2A",    "http://example.com/%25g1%2a"},
                {"http://example.com/foo|bar",   "http://example.com/foo|bar"},
                {"http://www.example.com/foo|bar", "http://example.com/foo|bar"},
        };

        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be error corrected as expected",
                         test[1], Normalisation.fixURLErrors(test[0]));
        }
    }

    /* In the URL-path, there can be space and plus. These will be distinct when normalised.
     * In the URL-arguments, space is often translated to plus so both space and plus will be normalised to plus */
    @Test
    public void testSpace() {
// TODO: What about escaped question marks?    {"http://example.com/%3fpath?q=%3f", "http://example.com/%3fpath?q=%3f"},
        final String[][] TESTS = new String[][]{
                {"http://example.com/%20 +path",           "http://example.com/%20%20+path"},
                {"http://example.com/+%20 path",           "http://example.com/+%20%20path"},
                {"http://example.com/path?foo=%20 +",      "http://example.com/path?foo=+++"},
                {"http://example.com/%20 +path?foo=%20 +", "http://example.com/%20%20+path?foo=+++"},
                {"http://example.com/+%20 path?foo=+%20 ", "http://example.com/+%20%20path?foo=+++"},
        };

        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be normalised as expected",
                         test[1], Normalisation.canonicaliseURL(test[0]));
        }
    }

    @Test
    public void testFaultyHARDURLNormalisation() {
        final String[][] TESTS = new String[][]{
                {"http://example.com/%",         "http://example.com/%25"},
                {"http://example.com/%%25",      "http://example.com/%25%25"},
                {"http://example.com/10% proof", "http://example.com/10%25%20proof"},
                {"http://example.com/%a%2A",     "http://example.com/%25a*"},
                {"http://example.com/%g1%2A",    "http://example.com/%25g1*"},
                {"http://example.com/hash#%23",  "http://example.com/hash#%23"},
        };

        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be normalised and error-corrected as expected",
                         test[1], Normalisation.canonicaliseURL(test[0]));
        }
    }

    @Test
    public void testCanonicaliseHost() throws URIException {
        final String[][] TESTS = new String[][]{
                {"http://example.com/",  "example.com"},
                {"http://example.com",   "example.com"},
                {"http://example.com ",  "example.com"},

                {"https://example.com/", "example.com"},
                {"https://example.com",  "example.com"},
                {"https://example.com ", "example.com"},
        };
        for (String[] test: TESTS) {
            assertEquals("The input '" + test[0] + "' should be reduced to the expected host",
                         test[1], Normalisation.canonicaliseHost(test[0]));
        }
    }

    @Test
    public void testNormalisationRemovePort() throws Exception{
       
        //When calling the removePort method the URL has already been partly normalised (https > http and lowercase)
        final String[][] TESTS = new String[][]{
            {"http://EXample.com/",  "http://example.com/"}, //2 uppercase
            {"http://example.com:80/",   "http://example.com/"},
            {"http://example.com:80/index.html",   "http://example.com/index.html"},
            {"http://example.com:80/index.html?param1=test#test",   "http://example.com/index.html?param1=test#test"}, //param and anchor
            {"https://example.com:444/index.html?param1=test&param2=test2#test#test2",   "http://example.com/index.html?param1=test&param2=test2#test#test2"}, //https+double param+Double anchor            
            {"https://example.com:444/INDEX.HTML?param1=test?illegalparam=true", "http://example.com/index.html?param1=test?illegalparam=true"} //double ?
        };        
        for (String[] test: TESTS) {
          assertEquals("The input '" + test[0] + "' url was not normalised as expected", 
                  test[1], Normalisation.canonicaliseURL( test[0],true,true)); 
        }        
        
    }
    @Test
    public void testRemovePort() throws Exception{
       
        //Direct test of the remove port method. The URL has not been partly normalised already. (lower case and https -> http)
        final String[][] TESTS = new String[][]{
            {"http://example.com/",  "http://example.com/"},
            {"http://example.com:80/",   "http://example.com/"},
            {"http://example.com:80/index.html",   "http://example.com/index.html"},
            {"http://example.com:80/index.html?param1=test#test",   "http://example.com/index.html?param1=test#test"}, //param and anchor
            {"https://example.com:444/index.html?param1=test&param2=test2#test#test2",   "https://example.com/index.html?param1=test&param2=test2#test#test2"}, //https+double param+Double anchor            
            {"https://example.com:444/index.html?param1=test?illegalparam=true", "https://example.com/index.html?param1=test?illegalparam=true"} //double ?
        };        
        for (String[] test: TESTS) {
          assertEquals("The input '" + test[0] + "' url was not normalised as expected", 
                  test[1], Normalisation.removePort( test[0]));
        }        
        
    }

    
    
    
    
    @Test
    public void testBase16ToBytes() {
        final String B16 = "sha1:5a3311bde611032119d6080eebf83a4a3b3475ed";
        final String B32 = "sha1:LIZRDPPGCEBSCGOWBAHOX6B2JI5TI5PN";
        assertEquals(B32, Normalisation.sha1HashAsBase32(B16));
    }
}
