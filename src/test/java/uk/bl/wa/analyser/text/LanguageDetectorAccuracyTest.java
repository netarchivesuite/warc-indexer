package uk.bl.wa.analyser.text;

import org.apache.tika.langdetect.opennlp.OpenNLPDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-language accuracy benchmark, NOT yet a hard pass/fail build gate:
 * for each language below, runs Tika's {@link OpenNLPDetector} against a
 * short sentence and a longer passage, and reports whether its single
 * top-ranked guess matches the expected language -- independent of
 * confidence/score, since that's a separate question for
 * {@link LanguageAnalyser} to decide.
 * <p>
 * This deliberately only covers real, grammatical, single-language text for
 * now (the "primary goal to be correct" case). Short/boilerplate/menu-like
 * fragments, mixed-language pages, and non-language "garbage" (JSON, code,
 * binary-ish text) are a separate, later concern -- don't let this class's
 * numbers stand in for those.
 * <p>
 * Sample text lives under {@code src/test/resources/language-detection/},
 * one {@code <code>_short.txt} and one {@code <code>_long.txt} file per
 * language, where {@code <code>} is the expected ISO 639-1 code (e.g.
 * {@code da_short.txt}, {@code da_long.txt}). Adding a language is just
 * dropping in two more files and one more {@code add(...)} call in
 * {@link #samples()}.
 * <p>
 * IMPORTANT: this text is hand-written from general multilingual knowledge,
 * not pulled from a verified parallel corpus (the usual sources, e.g.
 * Unicode's UDHR translation collection, are not reachable from the sandbox
 * this was drafted in). Spot-check the files you can personally read --
 * especially the Nordic languages -- before trusting this benchmark's
 * numbers, and fix/replace any sentence that looks off.
 */
public class LanguageDetectorAccuracyTest {

    private static final String RESOURCE_DIR = "/language-detection/";

    /**
     * One language's test material. Text is loaded lazily from resources.
     * {@code fileCode} names the resource files and {@code expectedIso6391}
     * is the code the detector should return; they're usually the same, but
     * differ for a variant that isn't a distinct detection target in its own
     * right (e.g. Flemish -- see {@link #samples()}).
     */
    private static final class Sample {
        final String fileCode;
        final String expectedIso6391;
        final String name;

        Sample(String fileCode, String expectedIso6391, String name) {
            this.fileCode = fileCode;
            this.expectedIso6391 = expectedIso6391;
            this.name = name;
        }

        Sample(String code, String name) {
            this(code, code, name);
        }

        String shortText() {
            return readResource(fileCode + "_short.txt");
        }

        String longText() {
            return readResource(fileCode + "_long.txt");
        }
    }

    private static String readResource(String fileName) {
        String path = RESOURCE_DIR + fileName;
        try (InputStream in = LanguageDetectorAccuracyTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + path
                        + " (expected under src/test/resources" + RESOURCE_DIR + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read test resource: " + path, e);
        }
    }

    private static List<Sample> samples() {
        List<Sample> s = new ArrayList<>();

        s.add(new Sample("da", "Danish"));
        s.add(new Sample("nb", "Norwegian Bokmål"));
        s.add(new Sample("nn", "Norwegian Nynorsk"));
        s.add(new Sample("sv", "Swedish"));
        s.add(new Sample("de", "German"));
        s.add(new Sample("en", "English"));
        s.add(new Sample("fr", "French"));
        s.add(new Sample("it", "Italian"));
        s.add(new Sample("es", "Spanish"));
        s.add(new Sample("pt", "Portuguese"));
        s.add(new Sample("nl", "Dutch"));
        // Flemish has no code of its own in the model (it's written in standard Dutch orthography), so this is expected to resolve to "nl" too.
        s.add(new Sample("nl-be", "nl", "Flemish (Belgian Dutch)"));
        s.add(new Sample("lb", "Luxembourgish"));
        s.add(new Sample("fi", "Finnish"));
        s.add(new Sample("is", "Icelandic"));
        s.add(new Sample("et", "Estonian"));
        s.add(new Sample("lv", "Latvian"));
        s.add(new Sample("lt", "Lithuanian"));
        s.add(new Sample("pl", "Polish"));
        s.add(new Sample("cs", "Czech"));
        s.add(new Sample("sk", "Slovak"));
        s.add(new Sample("hu", "Hungarian"));
        s.add(new Sample("ro", "Romanian"));
        s.add(new Sample("af", "Afrikaans"));
        s.add(new Sample("tr", "Turkish"));
        s.add(new Sample("el", "Greek"));
        s.add(new Sample("ru", "Russian"));
        s.add(new Sample("uk", "Ukrainian"));
        s.add(new Sample("ga", "Irish"));
        s.add(new Sample("cy", "Welsh"));
        s.add(new Sample("hr", "Croatian"));
        // No separate Egyptian Arabic code in the model (only generic "ara"/"ar"), so this is Modern Standard Arabic -- the register most Egyptian web text (news, government, formal pages) is actually written in, relevant for the Bibliotheca Alexandrina's archived collections.
        s.add(new Sample("ar", "Arabic"));

        return s;
    }

    @Test
    public void reportAccuracy() throws Exception {
        OpenNLPDetector detector = new OpenNLPDetector();

        List<Sample> samples = samples();
        int shortCorrect = 0, longCorrect = 0;

        System.out.printf("%-20s | %-6s | %-34s%n", "language", "length", "Tika OpenNLP");
        System.out.println("-".repeat(70));

        for (Sample sample : samples) {
            if (reportOne(detector, sample, "short", sample.shortText())) shortCorrect++;
            if (reportOne(detector, sample, "long", sample.longText())) longCorrect++;
        }

        int total = samples.size();
        System.out.println();
        System.out.printf("Tika OpenNLP: short %2d/%d (%.0f%%)   long %2d/%d (%.0f%%)%n",
                shortCorrect, total, 100.0 * shortCorrect / total,
                longCorrect, total, 100.0 * longCorrect / total);

        // Deliberately not asserting anything yet: this is a reporting
        // benchmark while we (a) spot-check/fix any wrong sample sentences,
        // (b) extend coverage to more languages, and (c) decide the
        // confidence-threshold question in LanguageAnalyser. Once satisfied,
        // turn the per-language booleans above into real assertions (e.g.
        // fail if accuracy drops below some % of total).
    }

    private boolean reportOne(OpenNLPDetector detector, Sample sample, String length, String text) {
        // detect(CharSequence) returns the single top-ranked candidate (same
        // one detectAll() would rank first), with its raw confidence bucket
        // intact -- we look only at .getLanguage() here, not .isUnknown(),
        // since correctness-of-top-guess and confidence-thresholding are
        // being evaluated separately (see class javadoc).
        LanguageResult result = detector.detect(text);
        String rawCode = (result == null) ? null : result.getLanguage();
        String code = LanguageAnalyser.toIso6391(rawCode);
        boolean ok = sample.expectedIso6391.equals(code);

        System.out.printf("%-20s | %-6s | %-34s%n",
                sample.name, length,
                (ok ? "OK   " : "FAIL ") + code
                        + (result != null ? " (" + result.getConfidence() + ")" : ""));

        return ok;
    }
}
