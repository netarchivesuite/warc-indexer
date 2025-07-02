package uk.bl.wa.indexer.delivery;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.bl.wa.indexer.WARCIndexerCommandOptions;
import uk.bl.wa.solr.SolrRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Stores documents as SolrXMLDocuments on the file system as 1 file/WARC, optionally gzipped.
 */
public class SingleFileDocumentConsumer extends BufferedDocumentConsumer {
    private static final Logger log = LoggerFactory.getLogger(SingleFileDocumentConsumer.class);

    private final boolean gzip;
    private final String outputFolder;
    private final WARCIndexerCommandOptions.OutputFormat outputFormat;

    private String filename = null;
    private Writer out = null;

    /**
     * Create a FilesystemDocumentConsumer based on the given configuration.
     * @param gzipOverride if true, the output will be gzipped, else it will be stored directly.
     * @param conf base setup for the DocumentConsumer. Values for maxDocuments, maxBytes and disableCommit will be
     *             taken from here, if present.
     * @param maxDocumentsOverride  if not null, this will override the value from conf "warc.solr.batch_size"
     * @param maxBytesOverride if not null, this will override the value from conf "warc.solr.batch_bytes"
     * @return a FilesystemDocumentconsumer, ready for use.
     * @throws IOException if the output file could not be created.
     */
    public SingleFileDocumentConsumer(
            String outputFolder, Config conf, WARCIndexerCommandOptions.OutputFormat outputFormat, Boolean gzipOverride,
            Integer maxDocumentsOverride, Long maxBytesOverride) throws IOException {
        super(conf, maxDocumentsOverride, maxBytesOverride, true); // Commit is implicit
        gzip = gzipOverride != null && gzipOverride;
        this.outputFolder = outputFolder + (outputFolder.endsWith("/") || outputFolder.endsWith("\\") ? "" : "/");
        this.outputFormat = outputFormat;

        Path outPath = new File(this.outputFolder).toPath();
        if (!Files.exists(outPath)) {
            try {
                Files.createDirectories(outPath);
            } catch (Exception e) {
                throw new IOException("Unable to create output folder '" + outPath + "'");
            }
        }
        log.info("Constructed " + this);
    }

    @Override
    void performFlush(List<SolrRecord> docs) throws IOException {
        if (out == null) {
            throw new IllegalStateException("Flush called but no output file is defined");
        }
        for (SolrRecord record: docs) {
            if( WARCIndexerCommandOptions.OutputFormat.xml.equals(outputFormat)) {
                record.writeXml(out);
            } else {
                out.write(record.toMemento().toJSON());
                out.write("\n");
            }
        }
        // "</add>" is not appended, as it only makes sense on close, so this is not strictly correct behaviour
        // as defined in DocumentConsumer#flush. Not much to do about that.
        out.flush();
    }

    @Override
    void performClose() throws IOException {
        endWARC();
    }

    @Override
    public void performCommit() {
        // No commit for filesystem
    }

    @Override
    public void startWARC(String warcfile) throws IOException {
        File inFile = new File(warcfile);
        if( WARCIndexerCommandOptions.OutputFormat.xml.equals(outputFormat)) {
            filename = outputFolder + inFile.getName() + ".xml" + (gzip ? ".gz" : "");
        } else {
            filename = outputFolder + inFile.getName() + ".jsonl" + (gzip ? ".gz" : "");
        }
        out = gzip ?
                new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(
                        new FileOutputStream(filename))), StandardCharsets.UTF_8) :
                new OutputStreamWriter(new BufferedOutputStream(
                        new FileOutputStream(filename)), StandardCharsets.UTF_8);

        if( WARCIndexerCommandOptions.OutputFormat.xml.equals(outputFormat)) {
            out.write("<add>");
        }
    }

    @Override
    public void endWARC() throws IOException {
        if (out == null) {
            return;
        }
        flush(); // Ensure all buffered documents are written
        if( WARCIndexerCommandOptions.OutputFormat.xml.equals(outputFormat)) {
            out.write("</add>");
        }
        out.flush();
        out.close();

        out = null;
        filename = null;
    }

    @Override
    public String toString() {
        return "SingleFileDocumentConsumer{" +
               "outputFolder='" + filename + '\'' +
               ", gzip=" + gzip +
               ", inner=" + super.toString() +
               '}';
    }
}
