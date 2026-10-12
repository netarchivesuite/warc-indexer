/**
 *
 */
package uk.bl.wa.analyser.text;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tika.langdetect.opennlp.OpenNLPDetector;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Instrument;

/**
 * Detects the (natural) language of extracted text using Apache Tika's
 * OpenNLP-based language detector ({@code tika-langdetect-opennlp}).
 * <p>
 * This replaces the previously used carrotsearch {@code langid-java}
 * library, which was archived/unmaintained upstream. The Tika/OpenNLP
 * detector supports substantially more languages (roughly 115-120 versus
 * ~97) and its bundled model was explicitly curated to drop languages
 * that were easily confused with others, which should help accuracy on
 * the major languages too.
 * <p>
 * One behavioural difference: the OpenNLP model reports languages using
 * ISO 639-3 (three-letter) codes (e.g. "eng", "dan"), occasionally with a
 * script/romanisation suffix (e.g. "zho-trad", "ben-rom"), whereas
 * langid-java reported ISO 639-1 (two-letter) codes (e.g. "en", "da").
 * Since the Solr {@code content_language} field and everything that reads
 * it (including SolrWayback's language facet) has always used ISO 639-1,
 * detected codes are mapped back to ISO 639-1 whenever a two-letter
 * equivalent exists, via a small hardcoded table. Languages with no
 * ISO 639-1 code (the majority of what this detector adds over the
 * previous one) are indexed using their native Tika/ISO 639-3 form.
 *
 * @author Toth
 */
public class LanguageAnalyser extends AbstractTextAnalyser
{
    private Logger log = LoggerFactory.getLogger(LanguageAnalyser.class);

    // The language detection model
    private LanguageDetector detector;

    /**
     * @param conf
     */
    public void configure(Config conf)
    {
        setEnabled(!conf.hasPath("warc.index.extract.content.language.enabled")
                || conf.getBoolean("warc.index.extract.content.language.enabled"));

        this.detector = new OpenNLPDetector();

        log.debug("Constructed language analyzer with enabled = " + isEnabled());
    }

    @Override
    public void analyse(String text, SolrRecord solr)
    {
        final long start = System.nanoTime();

        try
        {
            if (text != null && !text.isBlank())
            {
                // detectAll() resets the detector's internal state before adding
                // the text, so a single shared detector instance is safe to
                // reuse across documents.
                //
                // We deliberately do NOT gate on LanguageResult.isUnknown() /
                // isReasonablyCertain() here. Those are driven by raw-score
                // buckets (NONE <=0.20, LOW <=0.85, MEDIUM <=0.9, HIGH >0.9)
                // that don't behave usefully with this detector's ~180-language
                // model: probability mass is spread so thinly across candidates
                // that even long, completely unambiguous, single-language text
                // (confirmed experimentally -- see LanguageDetectorAccuracyTest)
                // scores well under 0.20 for its correct top candidate and is
                // therefore always bucketed NONE/isUnknown(). Gating on that
                // would silently discard almost every detection, correct or
                // not. The *ranking* of candidates is reliable even when the
                // absolute score is low (57/58 correct across a 32-language,
                // short+long benchmark), so -- matching the old langid-java
                // behaviour, which always returned its single best guess with
                // no confidence floor -- we simply take the top-ranked
                // candidate from detectAll().
                List<LanguageResult> results = detector.detectAll(text);

                if (!results.isEmpty())
                {
                    String language = results.get(0).getLanguage();
                    if (language != null && !language.isEmpty())
                    {
                        solr.addField(SolrFields.CONTENT_LANGUAGE, toIso6391(language));
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.error("Exception when determining language of this item: " + e.getMessage(), e);
            solr.addParseException(e);
        }

        Instrument.timeRel("TextAnalyzers#total", "LanguageAnalyzer#total", start);
    }

    /**
     * Maps an ISO 639-3 (three-letter) language code, as returned by Tika's
     * OpenNLP-based detector, back to the equivalent ISO 639-1 (two-letter)
     * code, so that {@code content_language} keeps using the same code
     * format it always has (produced by the previously used langid-java
     * library), rather than fragmenting old and newly indexed documents
     * across two different code formats.
     * <p>
     * Codes with no ISO 639-1 equivalent -- including Tika-specific
     * script/romanisation variants such as "zho-trad" or "ben-rom", which
     * are not part of any ISO 639 standard -- are returned unchanged, in
     * their native 3-letter (or 3-letter-plus-suffix) form.
     *
     * @param iso6393 the language code as returned by the detector
     * @return the ISO 639-1 code if a mapping exists, otherwise the input unchanged
     */
    static String toIso6391(String iso6393) {
        if (iso6393 == null) {
            return null;
        }
        String mapped = ISO_639_3_TO_1.get(iso6393);
        return mapped != null ? mapped : iso6393;
    }

    /**
     * ISO 639-3 (3-letter) to ISO 639-1 (2-letter) lookup, covering every
     * language that has a two-letter code, plus the handful of
     * Tika/OpenNLP-specific macrolanguage script-variant codes that have an
     * obvious 2-letter equivalent (zho-trad/zho-simp -&gt; zh).
     * <p>
     * Source: ISO 639 code tables, see
     * https://en.wikipedia.org/wiki/List_of_ISO_639_language_codes
     */
    private static final Map<String, String> ISO_639_3_TO_1 = new HashMap<>();
    static {
        ISO_639_3_TO_1.put("abk", "ab");
        ISO_639_3_TO_1.put("aar", "aa");
        ISO_639_3_TO_1.put("afr", "af");
        ISO_639_3_TO_1.put("aka", "ak");
        ISO_639_3_TO_1.put("sqi", "sq");
        ISO_639_3_TO_1.put("amh", "am");
        ISO_639_3_TO_1.put("ara", "ar");
        ISO_639_3_TO_1.put("arg", "an");
        ISO_639_3_TO_1.put("hye", "hy");
        ISO_639_3_TO_1.put("asm", "as");
        ISO_639_3_TO_1.put("ava", "av");
        ISO_639_3_TO_1.put("ave", "ae");
        ISO_639_3_TO_1.put("aym", "ay");
        ISO_639_3_TO_1.put("aze", "az");
        ISO_639_3_TO_1.put("bam", "bm");
        ISO_639_3_TO_1.put("bak", "ba");
        ISO_639_3_TO_1.put("eus", "eu");
        ISO_639_3_TO_1.put("bel", "be");
        ISO_639_3_TO_1.put("ben", "bn");
        ISO_639_3_TO_1.put("bis", "bi");
        ISO_639_3_TO_1.put("bos", "bs");
        ISO_639_3_TO_1.put("bre", "br");
        ISO_639_3_TO_1.put("bul", "bg");
        ISO_639_3_TO_1.put("mya", "my");
        ISO_639_3_TO_1.put("cat", "ca");
        ISO_639_3_TO_1.put("cha", "ch");
        ISO_639_3_TO_1.put("che", "ce");
        ISO_639_3_TO_1.put("nya", "ny");
        ISO_639_3_TO_1.put("zho", "zh");
        ISO_639_3_TO_1.put("chu", "cu");
        ISO_639_3_TO_1.put("chv", "cv");
        ISO_639_3_TO_1.put("cor", "kw");
        ISO_639_3_TO_1.put("cos", "co");
        ISO_639_3_TO_1.put("cre", "cr");
        ISO_639_3_TO_1.put("hrv", "hr");
        ISO_639_3_TO_1.put("ces", "cs");
        ISO_639_3_TO_1.put("dan", "da");
        ISO_639_3_TO_1.put("div", "dv");
        ISO_639_3_TO_1.put("nld", "nl");
        ISO_639_3_TO_1.put("dzo", "dz");
        ISO_639_3_TO_1.put("eng", "en");
        ISO_639_3_TO_1.put("epo", "eo");
        ISO_639_3_TO_1.put("est", "et");
        ISO_639_3_TO_1.put("ekk", "et"); // "Standard Estonian" -- distinct code in Tika's OpenNLP model
        ISO_639_3_TO_1.put("ewe", "ee");
        ISO_639_3_TO_1.put("fao", "fo");
        ISO_639_3_TO_1.put("fij", "fj");
        ISO_639_3_TO_1.put("fin", "fi");
        ISO_639_3_TO_1.put("fra", "fr");
        ISO_639_3_TO_1.put("fry", "fy");
        ISO_639_3_TO_1.put("ful", "ff");
        ISO_639_3_TO_1.put("gla", "gd");
        ISO_639_3_TO_1.put("glg", "gl");
        ISO_639_3_TO_1.put("lug", "lg");
        ISO_639_3_TO_1.put("kat", "ka");
        ISO_639_3_TO_1.put("deu", "de");
        ISO_639_3_TO_1.put("ell", "el");
        ISO_639_3_TO_1.put("kal", "kl");
        ISO_639_3_TO_1.put("grn", "gn");
        ISO_639_3_TO_1.put("guj", "gu");
        ISO_639_3_TO_1.put("hat", "ht");
        ISO_639_3_TO_1.put("hau", "ha");
        ISO_639_3_TO_1.put("heb", "he");
        ISO_639_3_TO_1.put("her", "hz");
        ISO_639_3_TO_1.put("hin", "hi");
        ISO_639_3_TO_1.put("hmo", "ho");
        ISO_639_3_TO_1.put("hun", "hu");
        ISO_639_3_TO_1.put("isl", "is");
        ISO_639_3_TO_1.put("ido", "io");
        ISO_639_3_TO_1.put("ibo", "ig");
        ISO_639_3_TO_1.put("ind", "id");
        ISO_639_3_TO_1.put("ina", "ia");
        ISO_639_3_TO_1.put("ile", "ie");
        ISO_639_3_TO_1.put("iku", "iu");
        ISO_639_3_TO_1.put("ipk", "ik");
        ISO_639_3_TO_1.put("gle", "ga");
        ISO_639_3_TO_1.put("ita", "it");
        ISO_639_3_TO_1.put("jpn", "ja");
        ISO_639_3_TO_1.put("jav", "jv");
        ISO_639_3_TO_1.put("kan", "kn");
        ISO_639_3_TO_1.put("kau", "kr");
        ISO_639_3_TO_1.put("kas", "ks");
        ISO_639_3_TO_1.put("kaz", "kk");
        ISO_639_3_TO_1.put("khm", "km");
        ISO_639_3_TO_1.put("kik", "ki");
        ISO_639_3_TO_1.put("kin", "rw");
        ISO_639_3_TO_1.put("kir", "ky");
        ISO_639_3_TO_1.put("kom", "kv");
        ISO_639_3_TO_1.put("kon", "kg");
        ISO_639_3_TO_1.put("kor", "ko");
        ISO_639_3_TO_1.put("kua", "kj");
        ISO_639_3_TO_1.put("kur", "ku");
        ISO_639_3_TO_1.put("lao", "lo");
        ISO_639_3_TO_1.put("lat", "la");
        ISO_639_3_TO_1.put("lav", "lv");
        ISO_639_3_TO_1.put("lvs", "lv"); // "Standard Latvian" -- same situation as ekk/est above
        ISO_639_3_TO_1.put("lim", "li");
        ISO_639_3_TO_1.put("lin", "ln");
        ISO_639_3_TO_1.put("lit", "lt");
        ISO_639_3_TO_1.put("lub", "lu");
        ISO_639_3_TO_1.put("ltz", "lb");
        ISO_639_3_TO_1.put("mkd", "mk");
        ISO_639_3_TO_1.put("mlg", "mg");
        ISO_639_3_TO_1.put("msa", "ms");
        ISO_639_3_TO_1.put("mal", "ml");
        ISO_639_3_TO_1.put("mlt", "mt");
        ISO_639_3_TO_1.put("glv", "gv");
        ISO_639_3_TO_1.put("mri", "mi");
        ISO_639_3_TO_1.put("mar", "mr");
        ISO_639_3_TO_1.put("mah", "mh");
        ISO_639_3_TO_1.put("mon", "mn");
        ISO_639_3_TO_1.put("nau", "na");
        ISO_639_3_TO_1.put("nav", "nv");
        ISO_639_3_TO_1.put("nde", "nd");
        ISO_639_3_TO_1.put("nbl", "nr");
        ISO_639_3_TO_1.put("ndo", "ng");
        ISO_639_3_TO_1.put("nep", "ne");
        ISO_639_3_TO_1.put("nor", "no");
        ISO_639_3_TO_1.put("nob", "nb");
        ISO_639_3_TO_1.put("nno", "nn");
        ISO_639_3_TO_1.put("oci", "oc");
        ISO_639_3_TO_1.put("oji", "oj");
        ISO_639_3_TO_1.put("ori", "or");
        ISO_639_3_TO_1.put("orm", "om");
        ISO_639_3_TO_1.put("oss", "os");
        ISO_639_3_TO_1.put("pli", "pi");
        ISO_639_3_TO_1.put("pus", "ps");
        ISO_639_3_TO_1.put("fas", "fa");
        ISO_639_3_TO_1.put("pes", "fa"); // "Iranian Persian" -- same situation as ekk/est above
        ISO_639_3_TO_1.put("pol", "pl");
        ISO_639_3_TO_1.put("por", "pt");
        ISO_639_3_TO_1.put("pan", "pa");
        ISO_639_3_TO_1.put("pnb", "pa"); // "Western Panjabi" -- same situation as ekk/est above
        ISO_639_3_TO_1.put("que", "qu");
        ISO_639_3_TO_1.put("ron", "ro");
        ISO_639_3_TO_1.put("roh", "rm");
        ISO_639_3_TO_1.put("run", "rn");
        ISO_639_3_TO_1.put("rus", "ru");
        ISO_639_3_TO_1.put("sme", "se");
        ISO_639_3_TO_1.put("smo", "sm");
        ISO_639_3_TO_1.put("sag", "sg");
        ISO_639_3_TO_1.put("san", "sa");
        ISO_639_3_TO_1.put("srd", "sc");
        ISO_639_3_TO_1.put("srp", "sr");
        ISO_639_3_TO_1.put("sna", "sn");
        ISO_639_3_TO_1.put("snd", "sd");
        ISO_639_3_TO_1.put("sin", "si");
        ISO_639_3_TO_1.put("slk", "sk");
        ISO_639_3_TO_1.put("slv", "sl");
        ISO_639_3_TO_1.put("som", "so");
        ISO_639_3_TO_1.put("sot", "st");
        ISO_639_3_TO_1.put("spa", "es");
        ISO_639_3_TO_1.put("sun", "su");
        ISO_639_3_TO_1.put("swa", "sw");
        ISO_639_3_TO_1.put("ssw", "ss");
        ISO_639_3_TO_1.put("swe", "sv");
        ISO_639_3_TO_1.put("tgl", "tl");
        ISO_639_3_TO_1.put("tah", "ty");
        ISO_639_3_TO_1.put("tgk", "tg");
        ISO_639_3_TO_1.put("tam", "ta");
        ISO_639_3_TO_1.put("tat", "tt");
        ISO_639_3_TO_1.put("tel", "te");
        ISO_639_3_TO_1.put("tha", "th");
        ISO_639_3_TO_1.put("bod", "bo");
        ISO_639_3_TO_1.put("tir", "ti");
        ISO_639_3_TO_1.put("ton", "to");
        ISO_639_3_TO_1.put("tso", "ts");
        ISO_639_3_TO_1.put("tsn", "tn");
        ISO_639_3_TO_1.put("tur", "tr");
        ISO_639_3_TO_1.put("tuk", "tk");
        ISO_639_3_TO_1.put("twi", "tw");
        ISO_639_3_TO_1.put("uig", "ug");
        ISO_639_3_TO_1.put("ukr", "uk");
        ISO_639_3_TO_1.put("urd", "ur");
        ISO_639_3_TO_1.put("uzb", "uz");
        ISO_639_3_TO_1.put("ven", "ve");
        ISO_639_3_TO_1.put("vie", "vi");
        ISO_639_3_TO_1.put("vol", "vo");
        ISO_639_3_TO_1.put("wln", "wa");
        ISO_639_3_TO_1.put("cym", "cy");
        ISO_639_3_TO_1.put("wol", "wo");
        ISO_639_3_TO_1.put("xho", "xh");
        ISO_639_3_TO_1.put("iii", "ii");
        ISO_639_3_TO_1.put("yid", "yi");
        ISO_639_3_TO_1.put("yor", "yo");
        ISO_639_3_TO_1.put("zha", "za");
        ISO_639_3_TO_1.put("zul", "zu");

        // Tika/OpenNLP-specific macrolanguage script variants that are not
        // themselves ISO 639 codes, but clearly map to an existing 2-letter one.
        ISO_639_3_TO_1.put("zho-trad", "zh");
        ISO_639_3_TO_1.put("zho-simp", "zh");
    }

}
