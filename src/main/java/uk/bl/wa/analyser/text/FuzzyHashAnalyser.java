/**
 * 
 */
package uk.bl.wa.analyser.text;

import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.typesafe.config.Config;

import eu.scape_project.bitwiser.utils.FuzzyHash;
import eu.scape_project.bitwiser.utils.SSDeep;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Instrument;

/**
 * @author anj
 *
 */
public class FuzzyHashAnalyser extends AbstractTextAnalyser {

    /**
     * @param conf
     */
    public void configure(Config conf) {
        if (!conf.hasPath("warc.index.extract.content.text_fuzzy_hash") || conf
                .getBoolean("warc.index.extract.content.text_fuzzy_hash")) {
            setEnabled(true);
        } else {
            setEnabled(false);
        }
    }

    /* (non-Javadoc)
     * @see uk.bl.wa.analyser.text.TextAnalyser#analyse(java.lang.String, uk.bl.wa.util.solr.SolrRecord)
     */
    @Override
    public void analyse(String text, SolrRecord solr) {
        final long start = System.nanoTime();
        // Canonicalize the text - strip newlines etc.
        Pattern whitespace = Pattern.compile( "\\s+" );
        Matcher matcher = whitespace.matcher( text );
        text = matcher.replaceAll( " " ).toLowerCase().trim();

        /* ---------------------------------------------------------- */

        // Add SSDeep hash for the text, to spot similar texts.
        SSDeep ssd = new SSDeep();
        FuzzyHash tfh;
        
        try {
            tfh = ssd.fuzzy_hash_buf( text.getBytes( "UTF-8" ) );
            solr.addField( SolrFields.SSDEEP_PREFIX + tfh.getBlocksize(), tfh.getHash() );
            solr.addField( SolrFields.SSDEEP_PREFIX + ( tfh.getBlocksize() * 2 ), tfh.getHash2() );
            // solr.addField( SolrFields.SSDEEP_NGRAM_PREFIX +
            // tfh.getBlocksize(), tfh.getHash() );
            // solr.addField( SolrFields.SSDEEP_NGRAM_PREFIX + (
            // tfh.getBlocksize() * 2 ), tfh.getHash2() );
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        Instrument.timeRel("TextAnalyzers#total", "FuzzyHashAnalyzer", start);
    }

}
