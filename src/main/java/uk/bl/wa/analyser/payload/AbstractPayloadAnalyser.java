/**
 * 
 */
package uk.bl.wa.analyser.payload;


import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AbstractParser;
import org.archive.io.ArchiveRecordHeader;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import uk.bl.wa.solr.SolrRecord;

/**
 * @author anj
 *
 */
public abstract class AbstractPayloadAnalyser {
    private static Logger log = LoggerFactory.getLogger(AbstractPayloadAnalyser.class );

    public void configure(Config conf) {
    }

    public abstract boolean shouldProcess(String mimeType);

    public abstract void analyse(String source, ArchiveRecordHeader header,
            InputStream tikainput, SolrRecord solr);

    protected class ParseRunner implements Runnable {
        AbstractParser parser;
        Metadata metadata;
        InputStream input;
        private SolrRecord solr;

        public ParseRunner( AbstractParser parser, InputStream tikainput, Metadata metadata, SolrRecord solr ) {
            this.parser = parser;
            this.metadata = metadata;
            this.input = tikainput;
            this.solr = solr;
        }

        @Override
        public void run() {
            try {
                parser.parse( input, null, metadata, null );
            } catch( Exception e ) {
                log.error(parser.getClass().getName() + ".parse(): "
                        + e.getMessage(), e);
                // Also record as a Solr PARSE_ERROR
                solr.addParseException("when parsing with "
                        + parser.getClass().getName(), e);
            }
        }
    }
    
    /**
     * This dynamically loads the available parser implementations on the
     * classpath. Passes in the provided configuration to get things set up.
     * 
     * @return
     */
    public static List<AbstractPayloadAnalyser> getPayloadAnalysers(
            Config conf) {

        // load our plugins
        ServiceLoader<AbstractPayloadAnalyser> serviceLoader = ServiceLoader
                .load(AbstractPayloadAnalyser.class);

        // Get the list:
        List<AbstractPayloadAnalyser> providers = new ArrayList<AbstractPayloadAnalyser>();
        for (AbstractPayloadAnalyser provider : serviceLoader) {
            // Perform any necessary configuration:
            provider.configure(conf);
            providers.add(provider);
        }

        return providers;
    }

    /**
     * Just for testing.
     * 
     * @param ignored
     */
    public static void main(String[] ignored) {

        // Get the config:
        Config conf = ConfigFactory.load();

        // create a new provider and call getMessage()
        List<AbstractPayloadAnalyser> providers = AbstractPayloadAnalyser
                .getPayloadAnalysers(conf);
        for (AbstractPayloadAnalyser provider : providers) {
            System.out.println(provider.getClass().getCanonicalName());
        }
    }
}
