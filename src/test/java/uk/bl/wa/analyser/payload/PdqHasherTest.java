package uk.bl.wa.analyser.payload;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.apache.commons.math3.distribution.LogNormalDistribution;

/**
 * Tests for PdqHasher — validates correctness of the reference-compatible
 * Java PDQ implementation.
 *
 * Run: javac pdq/PdqHasher.java pdq/PdqHasherTest.java && java pdq.PdqHasherTest
 *
 * Test suite:
 *   1.  Identical images → distance 0.
 *   2.  Reference image cross-check: hash of maria.png must equal the
 *       Python/C++ reference value (if the file is present).
 *   3.  Different images → large distance.
 *   4.  2D DCT bug check: H-gradient and V-gradient produce different hashes.
 *   5.  Quality: flat image low, high-contrast image high.
 *   6.  Hex round-trip correct.
 *   7.  Hamming distance arithmetic correct.
 *   8.  Window-size formula matches C++ reference.
 */
public class PdqHasherTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        
        
        // Hash an image
           
           BufferedImage img = ImageIO.read(new File("/home/teg/Downloads/maria.png"));
           long start= System.currentTimeMillis();
           
           PdqHasher.Result result = PdqHasher.hash(img);           
           System.out.println((System.currentTimeMillis()-start));
           
           String hex    = result.toHexString();   // 64-char hex, ThreatExchange wire format
           int quality   = result.quality;         // 0–100; discard if < 50
     
           System.out.println(result.toHexString());
           System.out.println(result.quality);
           
           System.exit(1);
        
        testIdenticalImages();
        testReferenceImage();
        testDifferentImages();
        test2DCTBugCheck();
        testQualityScores();
        testHexRoundTrip();
        testHammingArithmetic();
        testWindowSizeFormula();

        System.out.println("\n--- Results: " + passed + " passed, " + failed + " failed ---");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void testIdenticalImages() {
        BufferedImage img = makeCheckerboard(256, 256, 32);
        PdqHasher.Result r1 = PdqHasher.hash(img);
        PdqHasher.Result r2 = PdqHasher.hash(img);
        check("Identical images -> distance 0", r1.hammingDistance(r2) == 0, "dist=" + r1.hammingDistance(r2));
    }

    static void testReferenceImage() {
        // Cross-check against the Python/C++ reference value for maria.png.
        // This file is present in our test environment; skip gracefully if absent.
        java.io.File f = new java.io.File("/mnt/user-data/uploads/maria.png");
        if (!f.exists()) {
            System.out.println("  SKIP: testReferenceImage (maria.png not found)");
            return;
        }
        try {
            BufferedImage img = javax.imageio.ImageIO.read(f);
            PdqHasher.Result r = PdqHasher.hash(img);
            String expected = "aa74a9e4b3952eb95c5711e6a5b2dad1d5a852c62e0155b995ae2be4d823e31c";
            check("Reference image hash matches Python/C++ reference",
                r.toHexString().equals(expected),
                "got " + r.toHexString());
            check("Reference image quality = 100", r.quality == 100, "quality=" + r.quality);
        } catch (Exception e) {
            System.out.println("  FAIL: testReferenceImage - " + e.getMessage());
            failed++;
        }
    }

    static void testDifferentImages() {
        BufferedImage img1 = makeCheckerboard(256, 256, 32);
        BufferedImage img2 = makeGradient(256, 256);
        int dist = PdqHasher.hash(img1).hammingDistance(PdqHasher.hash(img2));
        check("Different images -> distance > 50", dist > 50, "dist=" + dist);
    }

    static void test2DCTBugCheck() {
        // A correct 2D DCT must distinguish horizontal from vertical gradients.
        // With only a 1D DCT (common bug), these would hash similarly.
        BufferedImage h = makeHorizontalGradient(128, 128);
        BufferedImage v = makeVerticalGradient(128, 128);
        int dist = PdqHasher.hash(h).hammingDistance(PdqHasher.hash(v));
        check("2D DCT check: H-gradient vs V-gradient differ (dist > 30)",
              dist > 30, "dist=" + dist + " (if 0-5, you have the 1D DCT bug!)");
    }

    static void testQualityScores() {
        int qFlat = PdqHasher.hash(makeSolid(256, 256, 128)).quality;
        check("Flat image quality < 20", qFlat < 20, "quality=" + qFlat);
        int qChecker = PdqHasher.hash(makeCheckerboard(256, 256, 4)).quality;
        check("High-contrast image quality > 50", qChecker > 50, "quality=" + qChecker);
    }

    static void testHexRoundTrip() {
        PdqHasher.Result r = PdqHasher.hash(makeGradient(128, 128));
        String hex = r.toHexString();
        check("Hex is 64 lowercase hex chars", hex.matches("[0-9a-f]{64}"), "got: " + hex);
        int[] parsed = PdqHasher.fromHexString(hex);
        check("Hex round-trip -> distance 0",
              PdqHasher.hammingDistance(r.words, parsed) == 0, "non-zero");
    }

    static void testHammingArithmetic() {
        int[] a = new int[16]; a[15] = 0xFFFF;
        int[] b = new int[16];
        check("Hamming: one full 16-bit flip = 16", PdqHasher.hammingDistance(a, b) == 16, "");
        check("Hamming: self = 0", PdqHasher.hammingDistance(a, a) == 0, "");
    }

    static void testWindowSizeFormula() {
        // Values from the C++ reference for common image sizes
        check("windowSize(1070, 64) = 9", PdqHasher.computeWindowSize(1070, 64) == 9, "");
        check("windowSize(700, 64) = 6",  PdqHasher.computeWindowSize(700,  64) == 6, "");
        check("windowSize(64, 64) = 1",   PdqHasher.computeWindowSize(64,   64) == 1, "");
        check("windowSize(1024, 64) = 8", PdqHasher.computeWindowSize(1024, 64) == 8, "");
    }

    // -----------------------------------------------------------------------
    // Image generators
    // -----------------------------------------------------------------------
    static BufferedImage makeCheckerboard(int w, int h, int bs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            img.setRGB(x, y, ((x/bs + y/bs) % 2 == 0) ? 0 : 0xFFFFFF);
        return img;
    }
    static BufferedImage makeGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = 255*x/w, u = 255*y/h;
            img.setRGB(x, y, (v<<16)|(u<<8)|((v+u)/2));
        }
        return img;
    }
    static BufferedImage makeHorizontalGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = 255*x/w; img.setRGB(x, y, (v<<16)|(v<<8)|v);
        }
        return img;
    }
    static BufferedImage makeVerticalGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = 255*y/h; img.setRGB(x, y, (v<<16)|(v<<8)|v);
        }
        return img;
    }
    static BufferedImage makeSolid(int w, int h, int grey) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int rgb = (grey<<16)|(grey<<8)|grey;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        return img;
    }

    // -----------------------------------------------------------------------
    static void check(String name, boolean ok, String detail) {
        if (ok) { System.out.println("  PASS: " + name); passed++; }
        else    { System.out.println("  FAIL: " + name + (detail.isEmpty() ? "" : " — " + detail)); failed++; }
    }
}