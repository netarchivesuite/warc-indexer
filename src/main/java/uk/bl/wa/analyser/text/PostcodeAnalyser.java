/**
 * 
 */
package uk.bl.wa.analyser.text;


import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.typesafe.config.Config;

import uk.bl.wa.extract.PostcodeGeomapper;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Instrument;

/**
 * @author anj
 *
 */
public class PostcodeAnalyser extends AbstractTextAnalyser {

    private static final Pattern postcodePattern = Pattern.compile( "[A-Z]{1,2}[0-9R][0-9A-Z]? [0-9][ABD-HJLNP-UW-Z]{2}" );

    /** */
    private PostcodeGeomapper pcg = new PostcodeGeomapper();
    
    /**
     * @param conf
     */
    public void configure(Config conf) {
        if (conf.getBoolean(
                        "warc.index.extract.content.text_extract_postcodes")) {
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
        // Postcode Extractor (based on text extracted by Tika)
        Matcher pcm = postcodePattern.matcher( text );
        Set<String> pcs = new HashSet<String>();
        while( pcm.find() )
            pcs.add( pcm.group() );
        for( String pc : pcs ) {
            solr.addField( SolrFields.POSTCODE, pc );
            String pcd = pc.substring( 0, pc.lastIndexOf( " " ) );
            solr.addField( SolrFields.POSTCODE_DISTRICT, pcd );
            String location = pcg.getLatLogForPostcodeDistrict( pcd );
            if( location != null )
                solr.addField( SolrFields.LOCATIONS, location );
        }
        Instrument.timeRel("TextAnalyzers#total", "PostcodeAnalyzer", start);
    }

}
