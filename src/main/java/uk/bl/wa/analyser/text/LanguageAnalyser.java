/**
 * 
 */
package uk.bl.wa.analyser.text;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.labs.langid.DetectedLanguage;
import com.carrotsearch.labs.langid.LangIdV3;
import com.typesafe.config.Config;

import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Instrument;

/**
 * @author Toth
 *
 */
public class LanguageAnalyser extends AbstractTextAnalyser 
{
    private Logger log = LoggerFactory.getLogger(LanguageAnalyser.class);
    
    // The language detection model
    private LangIdV3 langid;

    /**
     * @param conf
     */
    public void configure(Config conf)
    {
        setEnabled(!conf.hasPath("warc.index.extract.content.language.enabled")
                || conf.getBoolean("warc.index.extract.content.language.enabled"));
        
        this.langid = new LangIdV3();
        
        log.debug("Constructed language analyzer with enabled = " + isEnabled());
    }

    @Override
    public void analyse(String text, SolrRecord solr) 
    {
        final long start = System.nanoTime();
        
        try
        {
        	DetectedLanguage result = langid.classify(text, true);
            
            if (result != null) 
            {
                solr.addField(SolrFields.CONTENT_LANGUAGE, result.getLangCode());
            }
        }
        catch (IllegalArgumentException e) 
        {
            log.error("Exception when determining language of this item: " + e.getMessage(), e);
            solr.addParseException(e);
        }
        
        Instrument.timeRel("TextAnalyzers#total", "LanguageAnalyzer#total", start);
    }

}
