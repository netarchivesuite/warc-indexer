/**
 * 
 */
package uk.bl.wa.analyser.payload;


import java.io.InputStream;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.archive.io.ArchiveRecordHeader;

import com.google.common.base.Splitter;
import com.typesafe.config.Config;

import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Instrument;

/**
 * @author anj
 *
 */
public class FirstBytesAnalyser extends AbstractPayloadAnalyser {
    private static Logger log = LoggerFactory.getLogger( FirstBytesAnalyser.class );

    /** */

    private boolean extractContentFirstBytes = true;
    private int firstBytesLength = 32;

    public FirstBytesAnalyser() {
    }

    public void configure(Config conf) {
        this.extractContentFirstBytes = conf
                .getBoolean("warc.index.extract.content.first_bytes.enabled");
        this.firstBytesLength = conf
                .getInt("warc.index.extract.content.first_bytes.num_bytes");
        log.info("first_bytes config: " + this.extractContentFirstBytes + " "
                + this.firstBytesLength);
    }

    @Override
    public boolean shouldProcess(String mime) {
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * uk.bl.wa.analyser.payload.AbstractPayloadAnalyser#analyse(org.archive.io.
     * ArchiveRecordHeader, java.io.InputStream, uk.bl.wa.util.solr.SolrRecord)
     */
    @Override
    public void analyse(String source, ArchiveRecordHeader header, InputStream tikainput,
            SolrRecord solr) {
        final long firstBytesStart = System.nanoTime();
        // Pull out the first few bytes, to hunt for new format by magic:
        try {
            byte[] ffb = new byte[this.firstBytesLength];
            int read = tikainput.read(ffb);
            if (read >= 4) {
                String hexBytes = Hex.encodeHexString(ffb);
                solr.addField(SolrFields.CONTENT_FFB,
                        hexBytes.substring(0, 2 * 4));
                StringBuilder separatedHexBytes = new StringBuilder();
                for (String hexByte : Splitter.fixedLength(2).split(hexBytes)) {
                    separatedHexBytes.append(hexByte);
                    separatedHexBytes.append(" ");
                }
                if (this.extractContentFirstBytes) {
                    solr.addField(SolrFields.CONTENT_FIRST_BYTES,
                            separatedHexBytes.toString().trim());
                }
            }
        } catch (Exception i) {
            log.error(i + ": " + i.getMessage() + ";ffb; " + source + "@"
                    + header.getOffset());
        }
        Instrument.timeRel("WARCPayloadAnalyzers.analyze#total",
                "WARCPayloadAnalyzers.analyze#firstbytes", firstBytesStart);
    }

}
