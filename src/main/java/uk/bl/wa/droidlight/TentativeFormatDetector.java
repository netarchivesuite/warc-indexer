package uk.bl.wa.droidlight;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * TentativeFormatDetector - a standalone implementation of the extension- and
 * MIME-type-only fallback identification DROID itself uses for formats with NO
 * binary signature at all (DROID's own internal term for this is a "Tentative
 * Format" - see uk.gov.nationalarchives.droid.core.signature.droid6.FFSignatureFile,
 * buildFileExtensions()/addTentativeFormat(), and
 * uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier.matchExtensions()).
 *
 * Note: DROID itself only has an extension-based fallback (IdentificationMethod.
 * EXTENSION) - there is no equivalent MIME-type-based identification method in
 * real DROID (confirmed directly from its source: IdentificationMethod.java
 * defines exactly NULL/BINARY_SIGNATURE/EXTENSION/CONTAINER, nothing MIME-type
 * based). detectFromMimeType() here is this class's own addition, built for a
 * real practical need (WARC records carry an HTTP Content-Type header, a signal
 * DROID has no native concept of at all), reusing the same "tentative format"
 * data DROID's own extension fallback is built from - not a reproduction of
 * anything that exists in DROID itself.
 *
 * FULLY INDEPENDENT OF DroidSignatureVerifier / DroidSignatureAhoCorasickVerifier
 * -----------------------------------------------------------------------------------
 * This class parses the DROID_SignatureFile*.xml itself, with its own small,
 * self-contained StAX parser - deliberately NOT reusing DroidSignatureVerifier's
 * parseFileFormats() or any of its internal types. The two SignatureVerifier
 * classes' binary-matching logic is intentionally left completely untouched by
 * this class's existence: no shared fields, no shared parsing code, no coupling
 * in either direction. This does mean the signature file gets parsed twice if
 * you use both a SignatureVerifier and this class together (once by each), but
 * that's a deliberate tradeoff for keeping the two concerns fully separate,
 * rather than a shared-parsing optimization.
 *
 * A "tentative format" (DROID's own term) is any FileFormat entry with ZERO
 * InternalSignatureID children AND at least one declared Extension OR MIMEType -
 * this class's parser only ever looks for exactly that: it doesn't even bother
 * capturing InternalSignatureID children as data, it just counts them to
 * decide whether a given FileFormat qualifies.
 *
 * A few extensions genuinely map to more than one candidate format in real
 * DROID too (e.g. "dbf" -> multiple FoxPro Database variants; DROID itself
 * returns a List<FileFormat> for this exact reason). For simplicity, this
 * class keeps only the FIRST format seen per extension (in the signature
 * file's own document order) - a deliberate simplification, not a faithful
 * reproduction of DROID's "return all candidates" behavior.
 */
public class TentativeFormatDetector {

    // Populated by the constructor, read by the static detectFromFileName() /
    // detectFromMimeType() methods - this lets one instance be constructed once
    // (to load the mapping) while the actual lookups are called statically
    // afterward, as requested.
    private static final Map<String, FormatInfo> byExtension = new HashMap<>();
    private static final Map<String, FormatInfo> byMimeType = new HashMap<>();

    /**
     * Parses the given DROID signature file with this class's own independent
     * StAX parser, filters it down to "tentative formats" (zero
     * InternalSignatureID, at least one Extension), and populates the shared
     * extension map. Later calls to detectFromFileName() (static, callable
     * without holding a reference to this instance) will use whatever was
     * loaded here.
     *
     * @param signatureFile the DROID_SignatureFile*.xml to read
     */
    public TentativeFormatDetector(File signatureFile) throws Exception {
        int tentativeCount = 0;
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (InputStream in = new BufferedInputStream(new FileInputStream(signatureFile))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);

            String puid = null, name = null, mimeType = null;
            int internalSignatureCount = 0;
            List<String> extensions = null;
            boolean inFileFormat = false;
            boolean inExtensionTag = false;
            StringBuilder text = new StringBuilder();

            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("FileFormat")) {
                        inFileFormat = true;
                        puid = r.getAttributeValue(null, "PUID");
                        name = r.getAttributeValue(null, "Name");
                        mimeType = r.getAttributeValue(null, "MIMEType"); // may be null - not always present
                        internalSignatureCount = 0;
                        extensions = new ArrayList<>();
                    } else if (local.equals("InternalSignatureID") && inFileFormat) {
                        internalSignatureCount++; // content doesn't matter here, only the count
                    } else if (local.equals("Extension") && inFileFormat) {
                        inExtensionTag = true;
                        text.setLength(0);
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && inExtensionTag) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("Extension") && inExtensionTag) {
                        inExtensionTag = false;
                        String ext = text.toString().trim();
                        if (!ext.isEmpty()) extensions.add(ext.toLowerCase());
                    } else if (local.equals("FileFormat")) {
                        inFileFormat = false;
                        if (internalSignatureCount == 0 && (!extensions.isEmpty() || (mimeType != null && !mimeType.isEmpty()))) {
                            tentativeCount++;
                            FormatInfo info = new FormatInfo(puid, name, mimeType == null ? "" : mimeType);
                            for (String ext : extensions) {
                                // First one wins for an ambiguous extension - see class javadoc.
                                byExtension.putIfAbsent(ext, info);
                            }
                            if (mimeType != null && !mimeType.isEmpty()) {
                                // Same "first wins" simplification as byExtension - MIME types are
                                // if anything MORE likely to be shared across several tentative
                                // formats than extensions are (e.g. several plain-text-ish formats
                                // could all legitimately declare "text/plain").
                                byMimeType.putIfAbsent(mimeType.toLowerCase(), info);
                            }
                        }
                    }
                }
            }
            r.close();
        }
        System.out.println("Loaded " + tentativeCount + " tentative (zero-signature) formats: "
                + byExtension.size() + " unique extensions, "
                + byMimeType.size() + " unique MIME types, from " + signatureFile);
    }

    /**
     * Extracts the file extension from a filename or full path, matching the
     * "extension" concept DROID itself uses: the part after the LAST '.' in the
     * last path segment, lowercased. Returns null if there's no '.' at all, or
     * if the '.' is the very last character (no actual extension text after it).
     */
    static String getExtension(String filenameOrPath) {
        if (filenameOrPath == null) return null;
        int lastSlash = Math.max(filenameOrPath.lastIndexOf('/'), filenameOrPath.lastIndexOf('\\'));
        String name = (lastSlash >= 0) ? filenameOrPath.substring(lastSlash + 1) : filenameOrPath;

        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * Looks up a filename (or full path - only the last path segment and its
     * extension are used) against the loaded extension map.
     *
     * @param filename a filename or full path, e.g. "photo.jpg" or
     *                 "/home/teg/Desktop/photo.jpg"
     * @return the matching FormatInfo, or null if the file has no extension, or
     *         if its extension isn't in the loaded mapping
     */
    public static FormatInfo detectFromFileName(String filename) {
        String extension = getExtension(filename);
        if (extension == null) return null;
        return byExtension.get(extension);
    }

    /**
     * Normalizes a MIME type string for lookup - handles the fact that a real
     * HTTP Content-Type header (e.g. from a WARC record's HTTP response, which
     * is the motivating use case for this method) commonly carries extra
     * parameters the signature file's plain MIMEType attribute never has, e.g.
     * "text/html; charset=UTF-8" or "application/json;charset=utf-8". Strips
     * everything from the first ';' onward, trims whitespace, and lowercases.
     */
    static String normalizeMimeType(String mimeType) {
        if (mimeType == null) return null;
        String trimmed = mimeType.trim();
        int semicolon = trimmed.indexOf(';');
        if (semicolon >= 0) {
            trimmed = trimmed.substring(0, semicolon).trim();
        }
        if (trimmed.isEmpty()) return null;
        return trimmed.toLowerCase();
    }

    /**
     * Looks up a MIME type (e.g. from an HTTP Content-Type header) against the
     * loaded MIME-type map. Only ever finds a result for a "tentative format"
     * (see class javadoc) - a MIME type belonging to a format that DOES have a
     * real binary signature won't be found here, since this class only indexes
     * the zero-signature subset, same scope as detectFromFileName().
     *
     * @param mimeType a MIME type, with or without trailing parameters (e.g.
     *                 both "text/plain" and "text/plain; charset=UTF-8" work)
     * @return the matching FormatInfo, or null if mimeType is null/blank, or
     *         if it isn't in the loaded mapping
     */
    public static FormatInfo detectFromMimeType(String mimeType) {
        String normalized = normalizeMimeType(mimeType);
        if (normalized == null) return null;
        return byMimeType.get(normalized);
    }

    /** The generic "I couldn't identify this" MIME type - both DROID/nanite's
     *  own external convention when nothing at all is confidently identified,
     *  AND, confirmed directly against the real signature file, a genuine
     *  declared MIMEType on 9 real PUIDs (mostly extension-only entries like
     *  various CAD/Revit formats, but also x-fmt/450 Adobe InDesign Document,
     *  which HAS a real binary signature - so this value can legitimately come
     *  from binary matching itself, not just from an empty/no-match result). */
    private static final String GENERIC_OCTET_STREAM = "application/octet-stream";

    /**
     * Pipeline helper: given the result of a binary-signature detect() call
     * (from DroidSignatureVerifier, DroidSignatureAhoCorasickVerifier, or
     * DroidSignatureVerifierHeuristic - any of them, since they all return the
     * same DetectionResult[] shape), tries to do BETTER using this class's
     * extension-based lookup, in exactly two situations where binary matching
     * alone isn't a useful answer:
     *
     *   1. No binary match at all (binaryResult is empty) - the common case for
     *      formats with zero binary signature, like plain text, CSS, JSON.
     *   2. A binary match WAS found, but it's the generic
     *      "application/octet-stream" catch-all (see GENERIC_OCTET_STREAM's
     *      javadoc for why this is a real, not just theoretical, case) - not
     *      wrong, just unhelpfully generic, worth trying to improve on.
     *
     * In every OTHER case (a real, specific binary match), the original result
     * is returned completely unchanged - this method never overrides a
     * confident binary-signature result, only fills in for the cases where
     * binary matching had nothing useful to say.
     *
     * @param binaryResult the result of calling detect() on one of the binary
     *                     verifier classes - may be null or empty
     * @param filenameHint the last path segment of the file's URL, or a plain
     *                     filename - may be null/empty (see detectFromFileName()
     *                     for exactly how this is used)
     * @return binaryResult unchanged if it was already a specific, useful match;
     *         otherwise a single-element array wrapping this class's own
     *         extension-based match, if it found one; otherwise binaryResult
     *         unchanged (even if empty/octet-stream - there was nothing better
     *         to offer)
     */
    public static DetectionResult[] withFallback(DetectionResult[] binaryResult, String filenameHint) {
        return withFallback(binaryResult, filenameHint, null);
    }

    /**
     * Same as withFallback(DetectionResult[], String), but also tries a MIME
     * type hint (e.g. a WARC record's HTTP Content-Type header) if the
     * filename hint alone doesn't resolve to anything - tried in that order
     * (filename first) since a real file extension is typically a slightly
     * more specific/reliable signal than a possibly-generic server-reported
     * Content-Type, but either can resolve it.
     *
     * @param mimeTypeHint an HTTP Content-Type header value (with or without
     *                     trailing parameters) - may be null/empty
     */
    public static DetectionResult[] withFallback(DetectionResult[] binaryResult, String filenameHint, String mimeTypeHint) {
        boolean binaryResultIsUseless = (binaryResult == null || binaryResult.length == 0)
                || (binaryResult[0].mimeType != null && binaryResult[0].mimeType.equalsIgnoreCase(GENERIC_OCTET_STREAM));
        if (!binaryResultIsUseless) {
            return binaryResult; // already a specific, useful result - never override it
        }

        FormatInfo fallback = detectFromFileName(filenameHint);
        if (fallback == null) {
            fallback = detectFromMimeType(mimeTypeHint);
        }
        if (fallback == null) {
            return binaryResult; // couldn't do any better - keep the original (possibly empty/octet-stream) result
        }

        // FormatInfo has no version field (extension/MIME-based tentative
        // formats generally don't carry one the way versioned binary signatures
        // do) - passed as null, same as any other DetectionResult field that
        // isn't available for a given match.
        return new DetectionResult[]{ new DetectionResult(fallback.puid, fallback.name, fallback.mimeType, null) };
    }
}