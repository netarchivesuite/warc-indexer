/**
 * 
 */
package uk.bl.wa.analyser.text;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import uk.bl.wa.sentimentalj.Sentiment;
import uk.bl.wa.sentimentalj.SentimentalJ;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;

/**
 * @author anj
 *
 */
public class SentimentJTextAnalyser extends AbstractTextAnalyser {
    private static Logger log = LoggerFactory.getLogger(SentimentJTextAnalyser.class );

    /** */
    private static SentimentalJ sentij = new SentimentalJ();

    /**
     * @param conf
     */
    public void configure(Config conf) {
        if (conf.hasPath("warc.index.extract.content.text_sentimentj") && conf
                .getBoolean("warc.index.extract.content.text_sentimentj")) {
            setEnabled(true);
        } else {
            setEnabled(false);
        }
    }

    /**
     * 
     */
    public void analyse( String text, SolrRecord solr ) {
        // Sentiment Analysis:
        int sentilen = 10000;
        if( sentilen > text.length() )
            sentilen = text.length();
        String sentitext = text.substring( 0, sentilen );
        // metadata.get(HtmlFeatureParser.FIRST_PARAGRAPH);

        Sentiment senti = sentij.analyze( sentitext );
        double sentilog = Math.signum( senti.getComparative() ) * ( Math.log( 1.0 + Math.abs( senti.getComparative() ) ) / 40.0 );
        int sentii = ( int ) ( SolrFields.SENTIMENTS.length * ( 0.5 + sentilog ) );
        if( sentii < 0 ) {
            log.debug( "Caught a sentiment rating less than zero: " + sentii + " from " + sentilog );
            sentii = 0;
        }
        if( sentii >= SolrFields.SENTIMENTS.length ) {
            log.debug( "Caught a sentiment rating too large to be in range: " + sentii + " from " + sentilog );
            sentii = SolrFields.SENTIMENTS.length - 1;
        }
        // if( sentii != 3 )
        // log.debug("Got sentiment: " + sentii+" "+sentilog+" "+ SolrFields.SENTIMENTS[sentii] );
        // Map to sentiment scale:
        solr.addField( SolrFields.SENTIMENT, SolrFields.SENTIMENTS[ sentii ] );
        solr.addField( SolrFields.SENTIMENT_SCORE, "" + senti.getComparative() );
    }
}
