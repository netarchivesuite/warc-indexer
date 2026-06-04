package uk.bl.wa.analyser.payload;

import java.awt.image.BufferedImage;

/**
 * PDQ perceptual hash — pure Java, zero dependencies beyond java.awt.
 *
 * This is a faithful port of Meta's reference C++ implementation from
 * https://github.com/facebook/ThreatExchange/tree/main/pdq
 *
 * The algorithm (matching the C++ reference exactly):
 *   1.  Convert image to float luma (Y = 0.299R + 0.587G + 0.114B).
 *   2.  Apply a Jarosz windowed box-filter (2 passes of 1D box along rows
 *       then columns) to blur and anti-alias the full-resolution image.
 *   3.  Decimate to 64×64 by centre-pixel sampling.
 *   4.  Compute a gradient-based quality score (0–100).
 *   5.  Apply a 64→16 partial DCT: compute only the 16×16 low-frequency
 *       output using the asymmetric DCT matrix D where
 *           D[i][j] = sqrt(2/64) * cos(pi/(2*64) * (i+1) * (2j+1))
 *       (note: rows 1..16, NOT 0..15, so DC is excluded from the matrix
 *        but all 256 output cells ARE used for the median).
 *   6.  Find the median of all 256 DCT values using the Torben algorithm.
 *   7.  Threshold and pack into a Hash256 (16 unsigned shorts stored LSB-first,
 *       serialised high-word-first for the hex wire format).
 *
 * IMPORTANT DIFFERENCES from a naïve DCT-hash implementation:
 *   • Downsampling uses the Jarosz filter, NOT bilinear resize.
 *   • The DCT matrix rows start at i=1 (not i=0), so there is no DC row.
 *   • Median is over all 256 cells (not 255 AC cells).
 *   • Quality metric uses integer gradient arithmetic matching the C++.
 *
 * BSD-licensed, same as the Meta original.
 */
public class PdqHasher {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    public static final int HASH_BITS = 256;

    private static final int DCT_SIZE  = 64;   // luma buffer side
    private static final int KEEP      = 16;   // DCT output side (16x16 = 256 bits)
    private static final int NUM_PASSES = 2;   // Jarosz filter passes

    /** Luma weights (ITU-R BT.601, matching reference). */
    private static final float LUMA_R = 0.299f;
    private static final float LUMA_G = 0.587f;
    private static final float LUMA_B = 0.114f;

    /**
     * Precomputed 16×64 DCT matrix.
     * D[i][j] = sqrt(2/64) * cos(pi / (2*64) * (i+1) * (2j+1))
     * Stored row-major as a flat float[16*64].
     */
    private static final float[] DCT_MATRIX;
    static {
        DCT_MATRIX = new float[KEEP * DCT_SIZE];
        float scale = (float) Math.sqrt(2.0 / DCT_SIZE);
        double piOver2N = Math.PI / (2.0 * DCT_SIZE);
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < DCT_SIZE; j++) {
                DCT_MATRIX[i * DCT_SIZE + j] =
                    (float) (scale * Math.cos(piOver2N * (i + 1) * (2 * j + 1)));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public result type
    // -----------------------------------------------------------------------

    /**
     * A PDQ hash result: 256 bits packed as 16 unsigned shorts (stored in
     * an int[] for convenience), plus a quality score 0–100.
     *
     * Quality < 50 means the image is low-gradient / featureless; the
     * reference recommends discarding hashes with quality ≤ 49.
     *
     * Wire format: 64 hex chars, words w[15]..w[0] each as 4 hex chars.
     * This matches the ThreatExchange canonical format.
     */
    public static final class Result {
        /** 16 unsigned shorts stored as int[0..15], w[0] at index 0. */
        public final int[] words;
        public final int quality;

        Result(int[] words, int quality) {
            this.words = words;
            this.quality = quality;
        }

        /**
         * 64-character lowercase hex string (ThreatExchange wire format).
         * Words are emitted high-index first: w[15], w[14], …, w[0].
         */
        public String toHexString() {
            StringBuilder sb = new StringBuilder(64);
            for (int i = 15; i >= 0; i--) {
                sb.append(String.format("%04x", words[i] & 0xFFFF));
            }
            return sb.toString();
        }

        /** Hamming distance (0 = identical; ≤ 31 = similar per reference thresholds). */
        public int hammingDistance(Result other) {
            return PdqHasher.hammingDistance(this.words, other.words);
        }

        @Override
        public String toString() {
            return "PDQHash{hash=" + toHexString() + ", quality=" + quality + "}";
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Compute the PDQ hash of a BufferedImage. */
    public static Result hash(BufferedImage image) {
        int imgH = image.getHeight();
        int imgW = image.getWidth();

        // --- Step 1: convert to float luma ---
        float[] luma = new float[imgH * imgW];
        for (int r = 0; r < imgH; r++) {
            for (int c = 0; c < imgW; c++) {
                int rgb = image.getRGB(c, r);
                float rv = (rgb >> 16) & 0xFF;
                float gv = (rgb >>  8) & 0xFF;
                float bv =  rgb        & 0xFF;
                luma[r * imgW + c] = LUMA_R * rv + LUMA_G * gv + LUMA_B * bv;
            }
        }

        // --- Step 2: Jarosz filter (blur in-place, 2 passes) ---
        float[] tmp = new float[imgH * imgW];
        int wsAlongRows = computeWindowSize(imgW, DCT_SIZE);
        int wsAlongCols = computeWindowSize(imgH, DCT_SIZE);
        jaroszFilter(luma, tmp, imgH, imgW, wsAlongRows, wsAlongCols, NUM_PASSES);

        // --- Step 3: decimate to 64×64 ---
        float[][] luma64 = new float[DCT_SIZE][DCT_SIZE];
        for (int i = 0; i < DCT_SIZE; i++) {
            int srcRow = (int) ((i + 0.5) * imgH / DCT_SIZE);
            for (int j = 0; j < DCT_SIZE; j++) {
                int srcCol = (int) ((j + 0.5) * imgW / DCT_SIZE);
                luma64[i][j] = luma[srcRow * imgW + srcCol];
            }
        }

        // --- Step 4: quality metric ---
        int quality = qualityMetric(luma64);

        // --- Step 5: partial 2D DCT (64→16 each dimension) ---
        // T = D * luma64   (16×64)
        float[][] T = new float[KEEP][DCT_SIZE];
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < DCT_SIZE; j++) {
                float sum = 0f;
                for (int k = 0; k < DCT_SIZE; k++) {
                    sum += DCT_MATRIX[i * DCT_SIZE + k] * luma64[k][j];
                }
                T[i][j] = sum;
            }
        }
        // B = T * D^T   (16×16)
        float[][] B = new float[KEEP][KEEP];
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                float sum = 0f;
                for (int k = 0; k < DCT_SIZE; k++) {
                    sum += T[i][k] * DCT_MATRIX[j * DCT_SIZE + k]; // D^T[k][j] = D[j][k]
                }
                B[i][j] = sum;
            }
        }

        // --- Step 6: Torben median of all 256 values ---
        float[] flat = new float[KEEP * KEEP];
        for (int i = 0; i < KEEP; i++)
            System.arraycopy(B[i], 0, flat, i * KEEP, KEEP);
        float median = torbenMedian(flat);

        // --- Step 7: threshold and pack into 16 unsigned shorts ---
        int[] w = new int[16]; // w[0..15]
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                if (B[i][j] > median) {
                    int k = i * KEEP + j;
                    w[k >> 4] |= (1 << (k & 15));
                }
            }
        }

        return new Result(w, quality);
    }

    /** Hamming distance between two Result.words arrays. */
    public static int hammingDistance(int[] a, int[] b) {
        int dist = 0;
        for (int i = 0; i < 16; i++) {
            dist += Integer.bitCount((a[i] ^ b[i]) & 0xFFFF);
        }
        return dist;
    }

    /**
     * Parse a 64-char hex string (ThreatExchange wire format) into words[].
     * Words are stored low-index first; the hex string has w[15] first.
     */
    public static int[] fromHexString(String hex) {
        if (hex.length() != 64)
            throw new IllegalArgumentException("PDQ hex must be 64 chars, got " + hex.length());
        int[] w = new int[16];
        for (int i = 0; i < 16; i++) {
            // hex position: w[15] is at hex[0..3], w[0] is at hex[60..63]
            w[15 - i] = Integer.parseUnsignedInt(hex.substring(i * 4, i * 4 + 4), 16);
        }
        return w;
    }

    // -----------------------------------------------------------------------
    // Internal: Jarosz filter (matching C++ reference exactly)
    // -----------------------------------------------------------------------

    /** Jarosz window size: round-up formula from the C++ source. */
    static int computeWindowSize(int oldDim, int newDim) {
        return (oldDim + 2 * newDim - 1) / (2 * newDim);
    }

    /**
     * Apply the Jarosz filter in-place to {@code buf}.
     * {@code tmp} is a scratch buffer of the same size.
     * Each pass: box along rows → box along cols.
     */
    static void jaroszFilter(float[] buf, float[] tmp,
                             int numRows, int numCols,
                             int wsAlongRows, int wsAlongCols,
                             int nreps) {
        for (int pass = 0; pass < nreps; pass++) {
            // box along rows (stride 1 within each row)
            for (int r = 0; r < numRows; r++) {
                box1D(buf, r * numCols, tmp, r * numCols, numCols, 1, wsAlongRows);
            }
            // box along cols (stride numCols between rows)
            for (int c = 0; c < numCols; c++) {
                box1D(tmp, c, buf, c, numRows, numCols, wsAlongCols);
            }
        }
    }

    /**
     * 1D sliding box filter matching the C++ box1DFloat exactly.
     * Handles the four phases: accumulate / initial writes / full window / tail.
     *
     * @param in      source array
     * @param inOff   offset of first element in source
     * @param out     destination array
     * @param outOff  offset of first element in destination
     * @param len     number of elements
     * @param stride  element stride (1 for row-wise, numCols for col-wise)
     * @param ws      full window size
     */
    static void box1D(float[] in, int inOff,
                      float[] out, int outOff,
                      int len, int stride, int ws) {
        int half = (ws + 2) / 2;          // 7→4, 8→5, 9→5, 6→4
        int phase1 = half - 1;
        int phase2 = ws - half + 1;
        int phase3 = len - ws;
        int phase4 = half - 1;

        int li = inOff, ri = inOff, oi = outOff;
        float sum = 0f;
        int cw = 0;

        // Phase 1: accumulate, no output
        for (int i = 0; i < phase1; i++) {
            sum += in[ri]; cw++; ri += stride;
        }
        // Phase 2: output with growing window
        for (int i = 0; i < phase2; i++) {
            sum += in[ri]; cw++;
            out[oi] = sum / cw;
            ri += stride; oi += stride;
        }
        // Phase 3: full sliding window
        for (int i = 0; i < phase3; i++) {
            sum += in[ri]; sum -= in[li];
            out[oi] = sum / cw;
            li += stride; ri += stride; oi += stride;
        }
        // Phase 4: shrinking window at tail
        for (int i = 0; i < phase4; i++) {
            sum -= in[li]; cw--;
            out[oi] = sum / cw;
            li += stride; oi += stride;
        }
    }

    // -----------------------------------------------------------------------
    // Internal: quality metric (matching C++ pdqImageDomainQualityMetric)
    // -----------------------------------------------------------------------

    static int qualityMetric(float[][] buf) {
        int gradientSum = 0;
        // vertical gradients
        for (int i = 0; i < 63; i++) {
            for (int j = 0; j < 64; j++) {
                int d = (int) ((buf[i][j] - buf[i + 1][j]) * 100 / 255);
                gradientSum += Math.abs(d);
            }
        }
        // horizontal gradients
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 63; j++) {
                int d = (int) ((buf[i][j] - buf[i][j + 1]) * 100 / 255);
                gradientSum += Math.abs(d);
            }
        }
        return Math.min(100, gradientSum / 90);
    }

    // -----------------------------------------------------------------------
    // Internal: Torben median (O(n log n) via sort — reference uses O(n) Torben
    // but results must match; a sort gives the same median value)
    // -----------------------------------------------------------------------

    static float torbenMedian(float[] arr) {
        float[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 1)
            ? sorted[n / 2]
            : (sorted[n / 2 - 1] + sorted[n / 2]) / 2f;
    }

    // -----------------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------------

    /**
     * Usage: java pdq.PdqHasher image1.jpg [image2.jpg ...]
     * Prints: quality  hex-hash  filename
     * If two files, also prints Hamming distance.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: PdqHasher image1 [image2 ...]");
            System.exit(1);
        }
        Result[] results = new Result[args.length];
        for (int i = 0; i < args.length; i++) {
            BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(args[i]));
            if (img == null) { System.err.println("Cannot read: " + args[i]); System.exit(1); }
            results[i] = hash(img);
            System.out.printf("quality=%3d  hash=%s  %s%n",
                results[i].quality, results[i].toHexString(), args[i]);
        }
        if (args.length == 2) {
            int dist = results[0].hammingDistance(results[1]);
            System.out.printf("Hamming distance: %d  (%s)%n",
                dist, dist <= 31 ? "SIMILAR" : "different");
        }
    }
}