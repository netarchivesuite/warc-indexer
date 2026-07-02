/**
 * 
 */
package uk.bl.wa.analyser.payload;


import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tika.metadata.Metadata;
import org.archive.io.ArchiveRecordHeader;

import com.typesafe.config.Config;

import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;

/**
 * @author anj
 *
 */
public class ImageAnalyser extends AbstractPayloadAnalyser {
    private static Logger log = LoggerFactory.getLogger( ImageAnalyser.class );

    /** Maximum file size of images to attempt to parse */
    private long max_size_bytes = 1000;

    /** Random sampling rate */
    private double sampleRate = 100;
    private long sampleCount = 0;

    private boolean extractImageFeatures = false;

    public ImageAnalyser() {
    }

    public ImageAnalyser(Config conf) {
        this.configure(conf);
    }

    public void configure(Config conf) {
        this.extractImageFeatures = conf
                .getBoolean("warc.index.extract.content.images.enabled");
        log.info("Image feature extraction = " + this.extractImageFeatures);

        this.max_size_bytes = conf.getBytes("warc.index.extract.content.images.maxSizeInBytes");
        log.info("Image - max size in bytes " + this.max_size_bytes);
        this.sampleRate = 1.0 / conf
                .getInt("warc.index.extract.content.images.analysisSamplingRate");
        log.info("Image sample rate " + this.sampleRate);
    }

    @Override
    public boolean shouldProcess(String mime) {
        if( mime.startsWith( "image" ) ) {
            if (this.extractImageFeatures) {
                return true;
            }
        }
        return false;
    }

    /* (non-Javadoc)
     * @see uk.bl.wa.analyser.payload.AbstractPayloadAnalyser#analyse(org.archive.io.ArchiveRecordHeader, java.io.InputStream, uk.bl.wa.util.solr.SolrRecord)
     */
    @Override
    public void analyse(String source, ArchiveRecordHeader header,
            InputStream tikainput,
            SolrRecord solr) {
        // Set up metadata object to pass to parsers:
        Metadata metadata = new Metadata();
        // Skip large images:
        if( header.getLength() > max_size_bytes ) {
            return;
        }
        
        
        
        // Only attempt to analyse a random sub-set of the data:
        // (prefixing with static test of a final value to allow JIT to fully
        // optimise out the "OR Math.random()" bit)
        if (sampleRate >= 1.0 || Math.random() < sampleRate) {
            sampleCount++;

            int width = 0;
            int height = 0;

            // Try to load as BufferedImage first — one load covers both dimension extraction and perceptual hashing.
            java.awt.image.BufferedImage bufferedImage = null;
            try {
                bufferedImage = ImageIO.read(tikainput);
                if (bufferedImage != null) {
                    width = bufferedImage.getWidth();
                    height = bufferedImage.getHeight();
                    solr.addField(SolrFields.IMAGE_HEIGHT, "" + height);
                    solr.addField(SolrFields.IMAGE_WIDTH, "" + width);
                    solr.addField(SolrFields.IMAGE_SIZE, "" + (height * width));
                }
            } catch (Exception e) {
                // fall through to ImageReader fallback below
            }

            // Fallback: if BufferedImage failed (e.g. unsupported format such as
            // ICO, SVG, or exotic legacy formats), use ImageReader which can extract
            // dimensions without fully decoding the pixel data.
            if (bufferedImage == null) {
                ImageInputStream input = null;
                ImageReader reader = null;
                try {
                    input = ImageIO.createImageInputStream(tikainput);
                    reader = ImageIO.getImageReaders(input).next();
                    reader.setInput(input);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    solr.addField(SolrFields.IMAGE_HEIGHT, "" + height);
                    solr.addField(SolrFields.IMAGE_WIDTH, "" + width);
                    solr.addField(SolrFields.IMAGE_SIZE, "" + (height * width));
                } catch (Exception e) {
                    // known unsupported formats (ICO, SVG etc.) — suppress
                } finally {
                    if (reader != null) {
                        reader.dispose();
                    }
                }
            }

            // Perceptual hashing — only if BufferedImage loaded successfully
            // and image meets the minimum dimension threshold.
            if (bufferedImage != null && Math.min(width, height) >= 150) {

                // PDQ — all 8 dihedral variants in one pipeline pass
                String[] dihedralHashes = dk.kb.images.hash.PdqHasher.getAllDihedralHashes(bufferedImage);
                log.info("PDQ original hash: " + dihedralHashes[0]);
                for (int i = 0; i < dihedralHashes.length; i++) {
                    log.info("PDQ " + dk.kb.images.hash.PdqHasher.DIHEDRAL_NAMES[i] + ": " + dihedralHashes[i]);
                }

                // pHash
                String pHash = dk.kb.images.hash.PhashHasher.getHash(bufferedImage);
                log.info("pHash: " + pHash);
            }
        }
    }

    public long getSampleCount() {
        return this.sampleCount;
    }

}
