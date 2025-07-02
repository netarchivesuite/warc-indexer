package uk.bl.wa.indexer.delivery;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.bl.wa.opensearch.OpensearchImporter;
import uk.bl.wa.opensearch.OpensearchUrl;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.solr.SolrWebServer;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sends documents to a Opensearch installation.
 */
public class OpensearchDocumentConsumer extends BufferedDocumentConsumer {
    private static final Logger log = LoggerFactory.getLogger(OpensearchDocumentConsumer.class);

    private final String opensearchURL;
    private final OpensearchImporter opensearchImporter;

    /**
     * Create a SolrDocumentConsumer based on the given configuration.
     * @param opensearchURL the URL to an Opensearch installation.
     * @param opensearchUser the User for the Opensearch installation.
     * @param opensearchPassword the Password for the Opensearch installation.
     * @param conf base setup for the DocumentConsumer. Values for maxDocuments, maxBytes and disableCommit will be
     *             taken from here, if present.
     * @param maxDocumentsOverride  if not null, this will override the value from conf "warc.solr.batch_size"
     * @param maxBytesOverride if not null, this will override the value from conf "warc.solr.batch_bytes"
     * @param disableCommitOverride if not null, this will override the value from conf "warc.solr.disablecommit"
     * @return a SolrDocumentconsumer, ready for use.
     */
    public OpensearchDocumentConsumer(String opensearchURL, String opensearchUser, String opensearchPassword, Config conf,
                                         Integer maxDocumentsOverride, Long maxBytesOverride, Boolean disableCommitOverride) {
        super(conf, maxDocumentsOverride, maxBytesOverride, disableCommitOverride);

        this.opensearchURL = opensearchURL;
        OpensearchUrl eu = new OpensearchUrl(opensearchURL);
        if (!eu.isValid()) {
            throw new IllegalArgumentException("OpensearchUrl '" + opensearchURL + "' is not valid");
        }
        opensearchImporter = new OpensearchImporter(eu, opensearchUser, opensearchPassword);

        log.info("Constructed " + this);
    }

    @Override
    void performFlush(List<SolrRecord> docs) throws IOException {
        List<SolrInputDocument> solrDocs  = docs.stream().map(SolrRecord::getSolrDocument).collect(Collectors.toList());
        try {
            opensearchImporter.importDocuments(solrDocs);
        } catch (Exception e) {
            throw new IOException(
                    "Exception while flushing " + docs.size() + " records to Opensearch at " + opensearchURL, e);
        }
    }

    @Override
    void performClose() throws IOException {
        // No explicit action on close as we know that flush has already been called
    }

    @Override
    public void performCommit() throws IOException {
        // The OpensearchImporter does not support explicit commit
    }

    @Override
    public String toString() {
        return "OpensearchDocumentConsumer{" +
               "opensearchURL='" + opensearchURL + '\'' +
               ", inner=" + super.toString() +
               '}';
    }
}
