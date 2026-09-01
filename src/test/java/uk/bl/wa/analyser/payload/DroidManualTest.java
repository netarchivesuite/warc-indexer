package uk.bl.wa.analyser.payload;

import java.io.InputStream;

import uk.bl.wa.droidlight.DetectionResult;
import uk.bl.wa.droidlight.DroidSignatureVerifier;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * FolderScanner - runs DroidSignatureVerifier.detect() against every file in a
 * folder, printing a filename + top-detection-result line for each, so results
 * can be eyeballed at scale against the files' actual extensions.
 *
 * Hardcoded variables, no CLI args - meant to be run directly from an IDE, same
 * style as DroidSignatureVerifier's own main().
 *
 * One DroidSignatureVerifier instance is constructed ONCE (parsing the signature
 * file a single time) and reused for every file in the folder - see
 * DroidSignatureVerifier's constructor/detect() split, which is exactly what
 * makes this efficient for batch use.
 */
public class DroidManualTest {

    public static void main(String[] args) throws Exception {
        String signatureFile = "DROID_SignatureFile_V124.xml";
        String scan_folder = "/home/teg/Downloads/";

        File sigFile = new File(signatureFile);
        File folder = new File(scan_folder);

        if (!folder.isDirectory()) {
            System.out.println("Not a directory: " + folder);
            return;
        }

        // List files directly in the folder (not recursive - subdirectories are
        // skipped, not descended into).
        File[] entries = folder.listFiles();
        List<File> files = new ArrayList<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile()) {
                    files.add(f);
                }
            }
        }
        
        long start=System.currentTimeMillis();
        System.out.println("Found " + files.size() + " file(s) in " + folder);

        DroidSignatureVerifier verifier=null;
        try (InputStream in =  DroidManualTest.class.getClassLoader().getResourceAsStream(signatureFile)) {
            verifier = new DroidSignatureVerifier(in);
        }          
        //DroidSignatureAhoCorasickVerifier verifier= new DroidSignatureAhoCorasickVerifier(sigFile);
        
        System.out.println("\n===== Scanning " + files.size() + " file(s) =====\n");

        int errorCount = 0;
        for (File file : files) {
            try {
                System.out.println("Scanning:"+file);
                DetectionResult[] results = verifier.detect(file);

                if (results.length == 0) {
                    System.out.println("RESULT: " + file.getName() + "  ->  NO MATCH");
                } else {
                    // Top candidate on the main summary line.
                    System.out.println("RESULT: " + file.getName() + "  ->  " + results[0]);
                    // Any additional (lower-priority / suppressed) candidates, indented,
                    // so they don't clutter the primary eyeball-comparison line but are
                    // still visible if you want to check them.
                    for (int i = 1; i < results.length; i++) {
                        System.out.println("         (also matched: " + results[i] + ")");
                    }
                }
            } catch (Exception e) {
                // Don't let one unreadable/corrupt file abort the whole batch.
                errorCount++;
                System.out.println("RESULT: " + file.getName() + "  ->  ERROR: " + e);
            }
            System.out.println(); // blank line between files, since detect() itself prints
                                   // several diagnostic lines per file (kept, per earlier request)
        }

        System.out.println("===== Done. " + files.size() + " file(s) scanned, " + errorCount + " error(s). =====");
        System.out.println("Total scan time:"+(System.currentTimeMillis()-start));
    }
}