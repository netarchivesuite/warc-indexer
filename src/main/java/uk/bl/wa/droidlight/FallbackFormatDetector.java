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
 * FallbackFormatDetector - a standalone implementation of the extension- and
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
public class FallbackFormatDetector {

    // Populated by the constructor, read by the instance methods
    // detectFromFileName()/detectFromMimeType() below. Previously static
    // (shared across all instances) - changed to per-instance fields, since
    // static state here meant constructing a SECOND FallbackFormatDetector
    // with a different signature file would silently corrupt the first
    // instance's data. Also fixes an IDE warning about calling what looked
    // like state-dependent methods statically through an instance reference -
    // that warning was pointing at a real design issue, not just a style nit.
    private final Map<String, FormatInfo> byExtension = new HashMap<>();
    private final Map<String, FormatInfo> byMimeType = new HashMap<>();

    /**
     * Explicit, manually-curated overrides for MIME types where automatic
     * disambiguation among several EQUALLY-legitimate tentative-format
     * candidates isn't reliably possible. Checked before the general tiered
     * resolution below (see init()) and wins unconditionally for its target
     * MIME type - but only if a candidate with the given PUID actually exists
     * in the parsed signature file, so a stale entry here can't silently point
     * at nothing if a future PRONOM release removes or renumbers a format.
     *
     * "text/plain" is the motivating case: 12 different PRONOM entries declare
     * exactly this MIME type (various platform/encoding-specific text
     * classifications - Macintosh Text File, MS-DOS Text File, 7-bit ANSI
     * Text, 7-bit ASCII Text, etc.), each the legitimate, uncontested
     * "champion" of its own narrow, rarely-used extension (.ans, .asc, ...) -
     * meaning a purely algorithmic "prefer the extension champion" rule can't
     * distinguish between them; whichever happens to sit first in the
     * signature file's document order would win, which isn't a meaningful
     * signal at all. x-fmt/111 "Plain Text File" is the deliberately GENERAL
     * entry among these (declared for extension "txt", the one people
     * actually save files as) and is what a caller almost always wants for
     * ordinary web content served as text/plain - and, per the wish that
     * drove this: it's also what detectFromFileName() already returns for a
     * plain ".txt" file, so this override makes the two lookup paths agree.
     */
    private static final Map<String, String> MIME_TYPE_OVERRIDES = new HashMap<>();
    static {
        MIME_TYPE_OVERRIDES.put("text/plain", "x-fmt/111");
    }

    /**
     * MIME types where NONE of the competing tentative-format candidates are a
     * trustworthy general answer, so detectFromMimeType() deliberately returns
     * null for them rather than confidently guessing wrong.
     *
     * "text/html" is the motivating case: the only two zero-signature PRONOM
     * entries that declare MIMEType="text/html" are x-fmt/160 "Java Servlet
     * Page" (.jsp) and x-fmt/169 "PHP Script Page" (.php) - both describe
     * SERVER-SIDE SOURCE TEMPLATE formats, which happen to declare "text/html"
     * because that's what they RENDER TO when executed by a web server, not
     * because a raw .jsp/.php file itself looks like HTML. The real, general
     * "Hypertext Markup Language" PRONOM entries (fmt/96, fmt/471, etc.) all
     * have genuine binary signatures and are correctly excluded from this
     * class's tentative-format index entirely (see class javadoc) - meaning
     * there is no good "generic HTML" candidate available among the zero-
     * signature formats at all here. Picking either JSP or PHP as a general
     * "text/html" fallback answer would be confidently wrong for the
     * overwhelming majority of real HTML content (a normal, rendered web page
     * is not raw, unexecuted source code) - arguably worse than returning
     * nothing, since a caller might reasonably trust a returned result.
     *
     * Extension-based lookup is completely unaffected by this:
     * detectFromFileName("x.jsp") still correctly finds x-fmt/160 - this only
     * suppresses MIME-type-only guessing, for a MIME type where every
     * tentative-format candidate is misleading as a general default.
     */
    private static final Set<String> MIME_TYPE_SUPPRESSED = new HashSet<>(Arrays.asList(
            "text/html"
    ));

    /**
     * Additional MIME type ALIASES not declared anywhere in the signature
     * file itself, but real, historically-common values seen in actual HTTP
     * traffic for a format PRONOM does recognize under a different MIME
     * type. Applied as a final step after the normal tiered resolution (see
     * the constructor) - each alias reuses whatever FormatInfo the target
     * PUID already resolved to, so it stays automatically consistent with
     * that format's own real puid/name/mimeType/version rather than
     * inventing a separate record.
     *
     * "text/javascript" and "application/x-javascript" -> x-fmt/423
     * "JavaScript file": PRONOM's own signature file only declares
     * "application/javascript" for this format. Both of these are real,
     * historically common alternate MIME types for JavaScript actually seen
     * in production HTTP traffic - "text/javascript" is, in fact, the
     * CURRENTLY preferred value per the modern HTML/WHATWG spec (even though
     * PRONOM hasn't updated to reflect that), and "application/x-javascript"
     * is an older, pre-standardization convention some servers still use -
     * confirmed on a real, live URL (a JavaScript bundle served with exactly
     * this Content-Type).
     */
    private static final Map<String, String> MIME_TYPE_ALIASES = new HashMap<>();
    static {
        MIME_TYPE_ALIASES.put("text/javascript", "x-fmt/423");
        MIME_TYPE_ALIASES.put("application/x-javascript", "x-fmt/423");
    }

    /**
     * Parses the given DROID signature file with this class's own independent
     * StAX parser, filters it down to "tentative formats" (zero
     * InternalSignatureID, at least one Extension), and populates this
     * instance's extension/MIME-type maps. Later calls to
     * detectFromFileName()/detectFromMimeType() on THIS SAME instance will use
     * whatever was loaded here.
     *
     * @param signatureFile the DROID_SignatureFile*.xml to read
     */
    public FallbackFormatDetector(File signatureFile) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(signatureFile))) {
            init(in, signatureFile.toString());
        }
    }

    /**
     * Same as the File constructor, but reads from an already-open InputStream
     * instead - e.g. a classpath resource stream
     * (getClass().getClassLoader().getResourceAsStream("DROID_SignatureFile_V124.xml")),
     * which is what you need once the signature file ships as a Maven resource
     * packed inside a jar - see DroidSignatureVerifier.parseSignatures(InputStream)'s
     * javadoc for the full rationale (a plain java.io.File can't represent a
     * jar entry at all).
     *
     * Does not close the given stream - the caller retains ownership, same
     * convention as detect(InputStream) elsewhere in this codebase.
     *
     * @param signatureStream an already-open stream over the signature XML
     */
    public FallbackFormatDetector(InputStream signatureStream) throws Exception {
        init(signatureStream, "<input stream>");
    }

    /** A parsed tentative-format candidate, collected during the XML pass and
     *  resolved into byExtension/byMimeType afterward - see init()'s javadoc
     *  for why this two-phase approach exists (MIME-type tie-breaking needs to
     *  see ALL candidates for a MIME type before picking a winner, which a
     *  single streaming pass can't do). */
    private static final class Candidate {
        final String puid, name, mimeType, version;
        final List<String> extensions;
        Candidate(String puid, String name, String mimeType, String version, List<String> extensions) {
            this.puid = puid; this.name = name; this.mimeType = mimeType; this.version = version; this.extensions = extensions;
        }
    }

    /** Whether candidate c has already won at least one of its own extensions
     *  in byExtension - see the "Tier 1" comment above for why this matters. */
    private static boolean isExtensionChampion(Candidate c, Map<String, FormatInfo> byExtension) {
        for (String ext : c.extensions) {
            FormatInfo champion = byExtension.get(ext);
            if (champion != null && champion.puid.equals(c.puid)) return true;
        }
        return false;
    }

    /** Splits a possibly comma-joined MIMEType attribute value (a real pattern
     *  in the signature file - see the "Also splits" comment above) into
     *  trimmed, lowercased, non-empty parts. A single, non-comma-joined value
     *  just returns a one-element list. */
    private static List<String> splitMimeTypeParts(String mimeTypeAttribute) {
        List<String> parts = new ArrayList<>();
        for (String part : mimeTypeAttribute.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    private void init(InputStream in, String sourceDescription) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        {
            XMLStreamReader r = factory.createXMLStreamReader(in);

            String puid = null, name = null, mimeType = null, version = null;
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
                        version = r.getAttributeValue(null, "Version"); // may be null - not always present
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
                            candidates.add(new Candidate(puid, name, mimeType, version, extensions));
                        }
                    }
                }
            }
            r.close();
        }

        // byExtension: unchanged "first wins in document order" - extensions are
        // rarely ambiguous enough for this to matter in practice.
        for (Candidate c : candidates) {
            FormatInfo info = new FormatInfo(c.puid, c.name, c.mimeType == null ? "" : c.mimeType, c.version);
            for (String ext : c.extensions) {
                byExtension.putIfAbsent(ext, info);
            }
        }

        // Explicit overrides (see MIME_TYPE_OVERRIDES's javadoc) win first and
        // unconditionally, before the general tiered resolution below even runs.
        for (Candidate c : candidates) {
            if (c.mimeType == null || c.mimeType.isEmpty()) continue;
            for (String part : splitMimeTypeParts(c.mimeType)) {
                if (MIME_TYPE_SUPPRESSED.contains(part)) continue;
                String overridePuid = MIME_TYPE_OVERRIDES.get(part);
                if (overridePuid != null && overridePuid.equals(c.puid)) {
                    byMimeType.put(part, new FormatInfo(c.puid, c.name, c.mimeType, c.version));
                }
            }
        }

        // byMimeType: three-tier resolution. A MIME type is often shared by
        // several tentative formats (12 different PRONOM entries declare exactly
        // "text/plain", for instance) - picking whichever appears first in
        // document order used to mean an essentially arbitrary, sometimes
        // actively misleading winner (confirmed in practice: "text/plain"
        // resolved to x-fmt/14 "Macintosh Text File" - a legacy, line-ending-
        // specific classification nobody actually names a file after - purely
        // because it happens to sit at a lower PUID number than the correct,
        // general answer, x-fmt/111 "Plain Text File").
        //
        // Tier 1 - the strongest signal: a candidate that ALREADY won at least
        // one of its own extensions in byExtension above is a "confirmed
        // champion" for that format - preferring it here means
        // detectFromMimeType("text/plain") and detectFromFileName("x.txt")
        // agree on the SAME x-fmt/111 result, rather than two lookup paths
        // silently disagreeing about which of several plausible candidates is
        // "the" plain text format.
        // Tier 2 - other extension-bearing candidates not already covered.
        // Tier 3 - extension-less candidates, as a final fallback, so nothing
        // that used to be found stops being found.
        //
        // Also splits a comma-joined MIMEType attribute value (a real pattern
        // in the signature file itself - e.g. x-fmt/418 "Icon file format"
        // declares MIMEType="image/vnd.microsoft.icon, image/x-icon" as one
        // string) into separate lookup keys, so querying with EITHER value
        // alone finds it - previously only the exact, full joined string would
        // match, which a real caller almost never queries with verbatim.
        for (Candidate c : candidates) {
            if (c.mimeType == null || c.mimeType.isEmpty() || c.extensions.isEmpty()) continue;
            if (!isExtensionChampion(c, byExtension)) continue;
            FormatInfo info = new FormatInfo(c.puid, c.name, c.mimeType, c.version);
            for (String part : splitMimeTypeParts(c.mimeType)) {
                if (MIME_TYPE_SUPPRESSED.contains(part)) continue;
                byMimeType.putIfAbsent(part, info);
            }
        }
        for (Candidate c : candidates) {
            if (c.mimeType == null || c.mimeType.isEmpty() || c.extensions.isEmpty()) continue;
            FormatInfo info = new FormatInfo(c.puid, c.name, c.mimeType, c.version);
            for (String part : splitMimeTypeParts(c.mimeType)) {
                if (MIME_TYPE_SUPPRESSED.contains(part)) continue;
                byMimeType.putIfAbsent(part, info); // no-op if tier 1 already claimed this part
            }
        }
        for (Candidate c : candidates) {
            if (c.mimeType == null || c.mimeType.isEmpty()) continue;
            FormatInfo info = new FormatInfo(c.puid, c.name, c.mimeType, c.version);
            for (String part : splitMimeTypeParts(c.mimeType)) {
                if (MIME_TYPE_SUPPRESSED.contains(part)) continue;
                byMimeType.putIfAbsent(part, info); // no-op if tier 1 or 2 already claimed this part
            }
        }

        // Apply MIME type aliases (see MIME_TYPE_ALIASES's javadoc) - a final
        // step, so each alias reuses whatever FormatInfo the target PUID
        // already resolved to above.
        for (Map.Entry<String, String> alias : MIME_TYPE_ALIASES.entrySet()) {
            String aliasMimeType = alias.getKey();
            String targetPuid = alias.getValue();
            for (Candidate c : candidates) {
                if (c.puid.equals(targetPuid)) {
                    byMimeType.put(aliasMimeType, new FormatInfo(c.puid, c.name, c.mimeType, c.version));
                    break;
                }
            }
        }

        System.out.println("Loaded " + candidates.size() + " tentative (zero-signature) formats: "
                + byExtension.size() + " unique extensions, "
                + byMimeType.size() + " unique MIME types, from " + sourceDescription);
    }

    /**
     * Extracts the file extension from a filename or full path, matching the
     * "extension" concept DROID itself uses: the part after the LAST '.' in the
     * last path segment, lowercased. Returns null if there's no '.' at all, or
     * if the '.' is the very last character (no actual extension text after it).
     *
     * BUG FIX: previously didn't strip a URL query string or fragment before
     * looking for the extension - a real production case surfaced this:
     * "https://.../require-jquery.js?v=3fedeea" resolved to extension
     * "js?v=3fedeea" (the "." in ".js" was still the last "." in the string,
     * but everything after the "?" got dragged along with it), which isn't a
     * real, recognized extension at all, so lookup silently failed even
     * though the actual file extension was plainly ".js". This is a broad,
     * common case - cache-busting/versioning query parameters like "?v=..."
     * are extremely common on the modern web, not a rare edge case.
     */
    static String getExtension(String filenameOrPath) {
        if (filenameOrPath == null) return null;
        int lastSlash = Math.max(filenameOrPath.lastIndexOf('/'), filenameOrPath.lastIndexOf('\\'));
        String name = (lastSlash >= 0) ? filenameOrPath.substring(lastSlash + 1) : filenameOrPath;

        int queryOrFragment = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '?' || c == '#') {
                queryOrFragment = i;
                break;
            }
        }
        if (queryOrFragment >= 0) {
            name = name.substring(0, queryOrFragment);
        }

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
    public FormatInfo detectFromFileName(String filename) {
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
     * BUG FIX: an earlier version had no guard against querying with
     * "application/octet-stream" itself - a real production case showed this
     * resolving to x-fmt/441 "AutoCAD Database File Locking Information" for a
     * GitHub release-asset download that was actually a ZIP file, because the
     * CDN's own Content-Type happened to literally be "application/octet-
     * stream" (a real, common pattern - S3-backed download proxies frequently
     * report this generic value regardless of the actual content). Unlike an
     * ordinary ambiguous MIME type (several PLAUSIBLE candidates competing -
     * see MIME_TYPE_OVERRIDES/MIME_TYPE_SUPPRESSED), "application/octet-
     * stream" is PRONOM's own convention for "I don't know" - querying it is
     * meaningless BY DEFINITION, not just ambiguous, since a MIME type that
     * carries no real information can't point at a specific answer no matter
     * how many candidates declare it. Guarded here directly, rather than only
     * via MIME_TYPE_SUPPRESSED, since this is true unconditionally - not an
     * artifact of which particular candidates happen to compete for it.
     *
     * @param mimeType a MIME type, with or without trailing parameters (e.g.
     *                 both "text/plain" and "text/plain; charset=UTF-8" work)
     * @return the matching FormatInfo, or null if mimeType is null/blank,
     *         is exactly "application/octet-stream" (with or without
     *         parameters), or isn't in the loaded mapping
     */
    public FormatInfo detectFromMimeType(String mimeType) {
        String normalized = normalizeMimeType(mimeType);
        if (normalized == null) return null;
        if (normalized.equals(GENERIC_OCTET_STREAM)) return null;
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
    public DetectionResult[] withFallback(DetectionResult[] binaryResult, String filenameHint) {
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
    public DetectionResult[] withFallback(DetectionResult[] binaryResult, String filenameHint, String mimeTypeHint) {
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

        // BUG FIX: this used to hardcode null for version, on the (incorrect)
        // assumption that tentative formats generally don't carry one - a real
        // check found 280 of 560 tentative formats DO have a genuine Version
        // attribute (e.g. "Microsoft Word Document Template version=97-2003"),
        // which was being silently discarded here. FormatInfo now carries its
        // own version field (see its javadoc), threaded through properly.
        return new DetectionResult[]{ new DetectionResult(fallback.puid, fallback.name, fallback.mimeType, fallback.version) };
    }
}