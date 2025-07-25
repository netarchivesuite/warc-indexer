package uk.bl.wa.indexer.delivery;

import com.typesafe.config.Config;

import uk.bl.wa.indexer.WARCIndexerCommandOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * A DocumentConsumer is responsible for receiving {@link uk.bl.wa.solr.SolrRecord}s and passing them on to a
 * receiving system, such as Solr, Opensearch or the file system.
 */
public class DocumentConsumerFactory {
    private static final Logger log = LoggerFactory.getLogger(DocumentConsumerFactory.class);

    /**
     * Create a DocumentConsumer based on the given configuration.
     * Exactly one of outputdir, solrURL or opensearchURL must be defined.
     * if output is defined and oputputGZIP is true, a single gzipped file per WARC will be created.
     * if output is defined and oputputGZIP is false, a single plain file per WARC record will be created.
     * @param conf base setup for the DocumentConsumer.
     * @param outputFolder if defined the DocumentConsumer will write to a local file in the folder.
     * @param outputGZIP if true and outputDir is != null, the output will be gzipped.
     * @param solrURL if defined the DocumentConsumer will send to Solr.
     * @param opensearchURL if defined the Documentconsumer will send to Opensearch.
     * @param user the User for the Fulltext installation.
     * @param password the Password for the Fulltext  installation.
     * @param maxDocumentsOverride  if not null, this will override the value from conf "warc.solr.batch_size"
     * @param maxBytesOverride if not null, this will override the value from conf "warc.solr.batch_bytes"
     * @param disableCommitOverride if not null, this will override the value from conf "warc.solr.disablecommit"
     * @return a DocumentConsumer ready for consumption.
     * @throws IOException if the consumer could not be constructed.
     * @throws IllegalArgumentException if it was not possible to derive a proper setup.
     */
    public static DocumentConsumer createConsumer(
            Config conf, String outputFolder, WARCIndexerCommandOptions.OutputFormat outputFormat, Boolean outputGZIP, String solrURL, String opensearchURL, String user, String password,
            Integer maxDocumentsOverride, Long maxBytesOverride, Boolean disableCommitOverride) throws IOException {
        int outputs = (outputFolder == null ? 0 : 1) + (solrURL == null ? 0 : 1) + (opensearchURL == null ? 0 : 1);
        if (outputs > 1) {
            throw new IllegalArgumentException("Only 1 of either output, solr or opensearch can be specified");
        }

        if (outputFolder != null) {
            // The logic is horrible:
            if( WARCIndexerCommandOptions.OutputFormat.xml.equals(outputFormat)) {
                // outputGZIP determines whether the output will be a single (gzipped) file or multiple (plain) files
                return outputGZIP != null && outputGZIP ?
                        new SingleFileDocumentConsumer(
                                outputFolder, conf, outputFormat, outputGZIP, maxDocumentsOverride, maxBytesOverride):
                        new MultiFileDocumentConsumer(outputFolder, conf, outputFormat, outputGZIP);
            } else if( WARCIndexerCommandOptions.OutputFormat.jsonl.equals(outputFormat) ) {
                return new SingleFileDocumentConsumer(
                    outputFolder, conf, outputFormat, outputGZIP, maxDocumentsOverride, maxBytesOverride);
            } else {
                throw new IllegalArgumentException("No support code for format: " + outputFormat);
            }
        }
        if (solrURL != null) {
            return new SolrDocumentConsumer(
                    solrURL, conf, maxDocumentsOverride, maxBytesOverride, disableCommitOverride);
        }
        if (opensearchURL != null) {
            return new OpensearchDocumentConsumer(
                    opensearchURL, user, password, conf, maxDocumentsOverride, maxBytesOverride, disableCommitOverride);
        }
        throw new IllegalArgumentException("Either output, solr or opensearch must be specified");
    }
}
