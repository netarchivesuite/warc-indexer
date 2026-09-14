/**
 * 
 */
package uk.bl.wa.analyser.payload;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tika.metadata.Metadata;
import org.archive.io.ArchiveRecordHeader;

import com.typesafe.config.Config;

import dk.kb.images.hash.PdqHasher;
import dk.kb.images.hash.PhashHasher;
import uk.bl.wa.indexer.HTTPHeader;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;

/**
 * @author anj
 *
 */
public class ImageAnalyser extends AbstractPayloadAnalyser {
    private static Logger log = LoggerFactory.getLogger(ImageAnalyser.class);

    /** Maximum file size of images to attempt to parse */
    private long max_size_bytes = 1000;

    /** Random sampling rate */
    private double sampleRate = 100;
    private long sampleCount = 0;

    private boolean extractImageFeatures = false;
    
    /*Maybe extract to config3.xml
    # Minimum image dimension (shortest side in pixels) for perceptual hash calculation.
    # Images smaller than this in either dimension are skipped. Default: 150.
    "minImageDimensionForHashing" : 150,

    # Maximum pixel count (width * height) for perceptual hash calculation.
    # Very large images allocate several GB of RAM during hashing and can cause
    # OutOfMemoryError. Default: 8000000 (approx. 4000x2000 pixels).
    "maxImagePixelsForHashing" : 8000000
    */
    private long maxImagePixelsForHashing = 8_000_000L;
    private int minImageDimensionForHashing = 150;
    
    /** Whether to calculate perceptual hashes (PDQ and pHash) for images */
    private boolean calculateHashes = false;

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

        String calculateHashesProperty="warc.index.extract.content.images.calculateHashes";
        
        if (conf.hasPath(calculateHashesProperty)) {
            this.calculateHashes = conf.getBoolean(calculateHashesProperty);
        }
        log.info("Image hash calculation = " + this.calculateHashes);
    }

    @Override
    public boolean shouldProcess(String mime) {
        if (mime.startsWith("image")) {
            if (this.extractImageFeatures) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void analyse(String source, ArchiveRecordHeader header, HTTPHeader httpHeader, InputStream tikainput, SolrRecord solr) {
        // Set up metadata object to pass to parsers:
        Metadata metadata = new Metadata();
        // Skip large images:
        if (header.getLength() > max_size_bytes) {
            return;
        }

        // Only attempt to analyse a random sub-set of the data:
        // (prefixing with static test of a final value to allow JIT to fully
        // optimise out the "OR Math.random()" bit)
        if (sampleRate >= 1.0 || Math.random() < sampleRate) {
            // Increment number of images sampled:
            sampleCount++;

            int width = 0;
            int height = 0;
            BufferedImage bufferedImage = null;

            // Peek at dimensions first using ImageReader — cheap, no full pixel decode.
            // This avoids OutOfMemoryError when loading very large images (e.g. 23000x7000)
            // into a BufferedImage before we know their size.
            ImageInputStream imageInputStream = null;
            ImageReader reader = null;
            try {
                imageInputStream = ImageIO.createImageInputStream(tikainput);
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
                if (readers.hasNext()) {
                    reader = readers.next();
                    reader.setInput(imageInputStream);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    solr.addField(SolrFields.IMAGE_HEIGHT, "" + height);
                    solr.addField(SolrFields.IMAGE_WIDTH, "" + width);
                    solr.addField(SolrFields.IMAGE_SIZE, "" + (height * width));

                    // Only fully decode into BufferedImage if within safe pixel limit.
                    // Images above 8 megapixels can cause OutOfMemoryError when allocating
                    // the three full-resolution double[][] arrays needed by pHash
                    // (3 channels × width × height × 8 bytes per double).
                    if (calculateHashes
                            && Math.min(width, height) >= minImageDimensionForHashing
                            && (long) width * height <=   maxImagePixelsForHashing ) {
                        imageInputStream.seek(0);
                        bufferedImage = reader.read(0);
                    }
                }
            } catch (Exception e) {
               log.debug("Could not parse read image, error:"+e.getMessage());
            } finally {
                if (reader != null) reader.dispose();
                if (imageInputStream != null) {
                    try { imageInputStream.close(); } catch (Exception e) { /* suppress */ }
                }
            }

            // Perceptual hashing — only if BufferedImage was loaded successfully.
            if (bufferedImage != null) {
                addPerceptualHashes(bufferedImage, solr);
            }
        }
    }
    /**
     * Computes perceptual hashes (PDQ and pHash) for the given image and adds
     * them to the Solr record.
     *
     * PDQ produces all 8 dihedral variants (rotations and mirrors) in a single
     * pipeline pass at essentially no extra cost over a single hash. pHash
     * produces a single 64-bit hash.
     *
     * @param image  the decoded image, must not be null
     * @param solr   the Solr record to enrich with hash fields
     */
    private void addPerceptualHashes(BufferedImage image, SolrRecord solr) {
        // PDQ — all 8 dihedral variants in one pipeline pass
        String[] dihedralHashes = PdqHasher.getAllDihedralHashes(image);
        solr.addField(SolrFields.IMAGE_PDQ_HASH,          dihedralHashes[0]);
        solr.addField(SolrFields.IMAGE_PDQ_ROTATE90_HASH,  dihedralHashes[1]);
        solr.addField(SolrFields.IMAGE_PDQ_ROTATE180_HASH, dihedralHashes[2]);
        solr.addField(SolrFields.IMAGE_PDQ_ROTATE270_HASH, dihedralHashes[3]);
        solr.addField(SolrFields.IMAGE_PDQ_FLIPX_HASH,     dihedralHashes[4]);
        solr.addField(SolrFields.IMAGE_PDQ_FLIPY_HASH,     dihedralHashes[5]);
        solr.addField(SolrFields.IMAGE_PDQ_FLIPPLUS1_HASH,  dihedralHashes[6]);
        solr.addField(SolrFields.IMAGE_PDQ_FLIPMINUS1_HASH, dihedralHashes[7]);

        // PDQ bands — each dihedral hash split into 8 bands
        for (int d = 0; d < dihedralHashes.length; d++) {
            String dihedralName = PdqHasher.DIHEDRAL_NAMES[d];
            String[] bands = PdqHasher.splitIntoBands(dihedralHashes[d]);
            for (int b = 0; b < bands.length; b++) {
                solr.addField("image_pdq_" + dihedralName + "_band_" + b, bands[b]);
            }
        }

        
        // pHash and its 2 bands
        String pHash = PhashHasher.getHash(image);
        solr.addField(SolrFields.IMAGE_P_HASH, pHash);
        String[] pHashBands = PhashHasher.splitIntoBands(pHash);
        for (int i = 0; i < pHashBands.length; i++) {
            solr.addField("image_p_hash_band_" + i, pHashBands[i]);
        }
        
    }
    public long getSampleCount() {
        return this.sampleCount;
    }
}