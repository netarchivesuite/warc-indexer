/**
 * 
 */
package uk.bl.wa.analyser;


import java.util.List;

import com.typesafe.config.Config;

import uk.bl.wa.analyser.text.AbstractTextAnalyser;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Instrument;

/**
 * @author anj
 *
 */
public class TextAnalysers {

    /**
     * Set up analyser chain for text.
     */
    private List<AbstractTextAnalyser> analysers;

    public TextAnalysers(Config conf) {
        // Look up text analysers:
        analysers = AbstractTextAnalyser.getTextAnalysers(conf);
    }
    
    /**
     * Run all configured analysers on the text.
     * 
     * @param text
     * @param solr
     */
    public void analyse( SolrRecord solr ) {
        final long start = System.nanoTime();
        // Pull out the text:
        if( solr.getField( SolrFields.SOLR_EXTRACTED_TEXT ) != null ) {
            String text = ( String ) solr.getField( SolrFields.SOLR_EXTRACTED_TEXT ).getFirstValue();
            text = text.trim();
            if( !"".equals( text ) ) {
                // Already set (normalised by WARCPayloadAnalysers) on the Solr record at this point in the pipeline.
                String mimeType = solr.getField( SolrFields.SOLR_CONTENT_TYPE ) != null
                        ? ( String ) solr.getField( SolrFields.SOLR_CONTENT_TYPE ).getFirstValue()
                        : null;
                for( AbstractTextAnalyser ta : analysers ) {
                    if (ta.isEnabled() && ta.shouldProcess(mimeType, solr)) {
                        ta.analyse(text, solr);
                    }
                }
            }
        }
        Instrument.timeRel("WARCIndexer.extract#total", "TextAnalyzers#total", start);
    }
    
}
