package uk.bl.wa.util;


import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;

import static org.junit.Assert.*;

public class ValidateWARCNameMatchersTest {

    // Not a proper test as the user must inspect the output
    @Test
    public void testBasics() throws IOException {
        final URL CONFIG = Thread.currentThread().getContextClassLoader().getResource("arcnameanalyser.conf");
        final String WARCS =
                "/netarkiv/0101/filedir/15626-38-20070418024637-00385-sb-prod-har-001.statsbiblioteket.dk.arc\n" +
                "25666-33-20080221003533-00046-sb-prod-har-004.arc";
        ValidateWARCNameMatchers.validateRules(
                ValidateWARCNameMatchers.getRules(CONFIG.getPath()),
                new BufferedReader(new StringReader(WARCS)),
                true);
    }
}
