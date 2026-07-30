package uk.bl.wa.droidlight;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.util.*;

/**
 * DroidSignatureVerifierHeurestic - a fundamentally different matching STRATEGY
 * from DroidSignatureVerifier, kept as a completely separate class deliberately
 * (per explicit request) so the two can be A/B compared directly, rather than
 * replacing the existing, already-verified-correct DroidSignatureVerifier.
 *
 * WHAT'S ACTUALLY NEW HERE (everything else is reused, not reimplemented)
 * -----------------------------------------------------------------------------------
 * This class reuses DroidSignatureVerifier's parsing (parseSignatures,
 * parseFileFormats), per-signature matching (matchSignature), large-file
 * reading (readBoundedRegion), and priority resolution (applyPriorityResolution)
 * completely unchanged - none of DroidSignatureVerifier's own byte-matching
 * logic is duplicated or modified. What's new is purely the ORCHESTRATION
 * strategy: which signatures get checked, in what order, and when it's safe to
 * stop early - instead of always checking all ~2,258 signatures independently
 * regardless of content.
 *
 * THE CORE IDEA
 * -------------
 * A real web archive's format distribution is heavily long-tailed - the vast
 * majority of PRONOM's signatures are obscure legacy formats that will
 * essentially never appear. Checking all of them for every file is mostly
 * wasted work. This class instead:
 *
 *   1. Uses a "hint" (typically the last path segment of the file's URL, which
 *      may be empty, and may or may not have a recognizable extension) to look
 *      up which signatures are specifically associated with that extension,
 *      and tries those FIRST.
 *   2. ALSO always tries a small, fixed list of "common" web-content MIME types
 *      (see COMMON_MIME_TYPES) - since the hint can be missing entirely (the
 *      empty-hint case is common for HTML pages, whose URLs often don't end in
 *      an extension at all), and even a wrong/misleading hint shouldn't
 *      prevent finding a common format.
 *   3. Falls back to the full exhaustive scan (identical to
 *      DroidSignatureVerifier's own logic) ONLY if neither of the above finds
 *      anything - this is the correctness safety net for the long tail: an
 *      obscure or unexpected format is still found, just more slowly.
 *
 * WHY THIS IS SAFE FOR PRIORITY RESOLUTION (the one real correctness risk)
 * -----------------------------------------------------------------------------------
 * Matching isn't strictly "first match wins" - DROID's HasPriorityOverFileFormatID
 * mechanism means a match against one format can be overridden by a DIFFERENT,
 * more specific format that also matches (e.g. JAR overriding generic ZIP, ODP
 * overriding generic ZIP - confirmed directly: a real .odp file raw-matches
 * BOTH signature 200 [generic ZIP] and 310 [ODP-specific], with priority
 * resolution correctly picking ODP). Naively stopping at the first match found
 * in the fast-path candidate list would risk returning the LESS specific
 * answer in exactly these cases.
 *
 * Fixed by precomputing, once at construction time, a REVERSE index:
 * couldBeOverriddenByFormatIds, mapping each format to the (usually empty) list
 * of OTHER formats that have priority over it. On a fast-path match:
 *   - If nothing could override the matched format -> return immediately, true
 *     zero-risk short-circuit (this is the overwhelming majority of formats).
 *   - If something COULD override it -> check ONLY that small, specific set of
 *     overriding formats' signatures (not the full 2,258), then resolve the
 *     final winner using DroidSignatureVerifier's own applyPriorityResolution()
 *     (reused unchanged - not reimplemented, to avoid any risk of the two
 *     classes' priority logic silently diverging).
 *
 * WHAT'S KNOWINGLY TRADED FOR SPEED
 * -----------------------------------------------------------------------------------
 * DroidSignatureVerifier's detect() returns up to 10 candidates - the resolved
 * winner plus any other raw matches that lost priority resolution, as lower-
 * confidence "also matched" entries. In the FAST PATH (extension/common-format
 * hit), this class only ever discovers the specific formats it deliberately
 * checked (the hint's candidates, the common list, and their targeted
 * overriders) - it does NOT rediscover every other coincidental raw match a
 * full scan would have found, since finding those isn't attempted. The TOP
 * result is fully correct either way (that's what the override-chasing logic
 * guarantees); only the LENGTH and completeness of the "also matched" tail can
 * differ from a full scan, and only in the fast path. The fallback path (used
 * whenever the fast path finds nothing) is a full scan and behaves identically
 * to DroidSignatureVerifier in every respect. This is a deliberate speed/
 * completeness tradeoff, per explicit direction that speed matters more than
 * exhaustive precision here.
 */
public class DroidSignatureVerifierHeuristic {

    /**
     * Common web-content MIME types, always checked first regardless of the
     * hint (the hint can be empty - e.g. HTML pages often have no extension in
     * their URL at all). This is a REASONABLE DEFAULT based on general
     * knowledge of typical web-archive content, NOT derived from any real
     * frequency data - DROID's own signature file has no notion of "how common
     * is this format" at all. Deliberately left as a plain, mutable, public
     * static field so it can be tuned once real distribution data is available
     * (e.g. from a large-scale test run) - a specific comparison the user
     * intends to run to validate/refine this list themselves.
     */
    public static List<String> COMMON_MIME_TYPES = new ArrayList<>(Arrays.asList(
            "text/html",
            "text/plain",
            "text/css",
            "application/javascript",
            "text/javascript",
            "application/json",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/svg+xml",
            "image/vnd.microsoft.icon",
            "image/x-icon",
            "application/pdf",
            "video/mp4",
            "audio/mpeg",
            "application/xml",
            "text/xml",
            "application/zip",
            "font/woff",
            "font/woff2"
    ));

    private final List<DroidSignatureVerifier.InternalSignatureDef> signatures;
    private final List<DroidSignatureVerifier.FileFormatDef> formats;

    private final Map<Integer, DroidSignatureVerifier.InternalSignatureDef> signatureById = new HashMap<>();
    private final Map<Integer, DroidSignatureVerifier.FileFormatDef> formatById = new HashMap<>();
    // Reverse of FileFormatDef.signatureIds - which format(s) does a given signature belong to.
    private final Map<Integer, List<DroidSignatureVerifier.FileFormatDef>> formatsForSignatureId = new HashMap<>();
    // Reverse of FileFormatDef.hasPriorityOverFormatIds - which format(s) could override a given one.
    private final Map<Integer, List<Integer>> couldBeOverriddenByFormatIds = new HashMap<>();
    // extension (lowercase, no dot) -> signatures for formats declaring that extension.
    private final Map<String, List<DroidSignatureVerifier.InternalSignatureDef>> signaturesByExtension = new HashMap<>();
    // Always-tried common-format signatures, in COMMON_MIME_TYPES order, deduplicated.
    private final List<DroidSignatureVerifier.InternalSignatureDef> commonSignatures = new ArrayList<>();

    /**
     * Parses the given DROID signature file - reusing DroidSignatureVerifier's
     * own parser directly for the core signature/format data (nothing about
     * DroidSignatureVerifier itself is modified) - then builds the extra
     * indexes this class's ordering strategy needs (extension lookup, priority
     * reverse-index, common-format list).
     */
    public DroidSignatureVerifierHeuristic(File signatureFile) throws Exception {
        System.out.println("Parsing full signature structure (anchors + fragments + endianness): " + signatureFile);
        long t0 = System.nanoTime();
        this.signatures = DroidSignatureVerifier.parseSignatures(signatureFile);
        this.formats = DroidSignatureVerifier.parseFileFormats(signatureFile);
        DroidSignatureVerifier.precomputeOrdering(signatures); // essential - see its own javadoc
        long t1 = System.nanoTime();
        System.out.printf("  Parsed %,d signatures and %,d file formats in %.1f ms%n",
                signatures.size(), formats.size(), (t1 - t0) / 1e6);

        for (DroidSignatureVerifier.InternalSignatureDef sig : signatures) {
            signatureById.put(sig.id, sig);
        }
        for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
            formatById.put(fmt.id, fmt);
            for (int sigId : fmt.signatureIds) {
                formatsForSignatureId.computeIfAbsent(sigId, k -> new ArrayList<>()).add(fmt);
            }
        }
        for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
            for (int suppressedId : fmt.hasPriorityOverFormatIds) {
                couldBeOverriddenByFormatIds.computeIfAbsent(suppressedId, k -> new ArrayList<>()).add(fmt.id);
            }
        }

        // Extensions aren't captured by DroidSignatureVerifier.parseFileFormats() -
        // parsed here independently (small, self-contained pass) rather than
        // modifying that shared method.
        Map<Integer, List<String>> extensionsByFormatId = parseExtensions(signatureFile);
        for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
            List<String> extensions = extensionsByFormatId.get(fmt.id);
            if (extensions == null) continue;
            for (String ext : extensions) {
                List<DroidSignatureVerifier.InternalSignatureDef> list =
                        signaturesByExtension.computeIfAbsent(ext, k -> new ArrayList<>());
                for (int sigId : fmt.signatureIds) {
                    DroidSignatureVerifier.InternalSignatureDef sig = signatureById.get(sigId);
                    if (sig != null && !list.contains(sig)) list.add(sig);
                }
            }
        }

        Set<Integer> addedSigIds = new HashSet<>();
        for (String mimeType : COMMON_MIME_TYPES) {
            for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
                if (fmt.mimeType == null) continue;
                if (!mimeTypeMatches(fmt.mimeType, mimeType)) continue;
                for (int sigId : fmt.signatureIds) {
                    if (addedSigIds.add(sigId)) {
                        DroidSignatureVerifier.InternalSignatureDef sig = signatureById.get(sigId);
                        if (sig != null) commonSignatures.add(sig);
                    }
                }
            }
        }
        long t2 = System.nanoTime();
        System.out.printf("  Built extension/priority/common-format indexes in %.1f ms (%,d common-format signatures)%n",
                (t2 - t1) / 1e6, commonSignatures.size());
    }

    /** Whether a (possibly comma-joined, e.g. "image/vnd.microsoft.icon,
     *  image/x-icon" - a real pattern found in the signature file itself)
     *  MIMEType attribute value contains the given target MIME type. */
    private static boolean mimeTypeMatches(String mimeTypeAttribute, String target) {
        for (String part : mimeTypeAttribute.split(",")) {
            if (part.trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    /** Small, self-contained Extension parser - deliberately independent of
     *  DroidSignatureVerifier.parseFileFormats() (which doesn't capture this
     *  data) rather than modifying that shared method. */
    private static Map<Integer, List<String>> parseExtensions(File signatureFile) throws Exception {
        Map<Integer, List<String>> result = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (InputStream in = new BufferedInputStream(new FileInputStream(signatureFile))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            int currentId = -1;
            boolean inFileFormat = false;
            boolean inExtensionTag = false;
            StringBuilder text = new StringBuilder();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("FileFormat")) {
                        inFileFormat = true;
                        currentId = Integer.parseInt(r.getAttributeValue(null, "ID"));
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
                        String ext = text.toString().trim().toLowerCase();
                        if (!ext.isEmpty()) {
                            result.computeIfAbsent(currentId, k -> new ArrayList<>()).add(ext);
                        }
                    } else if (local.equals("FileFormat")) {
                        inFileFormat = false;
                    }
                }
            }
            r.close();
        }
        return result;
    }

    /** Same extension-extraction semantics as TentativeFormatDetector.getExtension()
     *  - kept as an independent local copy rather than a cross-class dependency,
     *  same reasoning as this class's other self-contained parsing. */
    static String extractExtension(String hint) {
        if (hint == null) return null;
        int lastSlash = Math.max(hint.lastIndexOf('/'), hint.lastIndexOf('\\'));
        String name = (lastSlash >= 0) ? hint.substring(lastSlash + 1) : hint;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * @param targetFile the file to identify
     * @param hint       the last path segment of the file's URL, or a plain
     *                   filename - may be empty or null. Only used if it ends
     *                   with a recognizable extension; otherwise ignored (the
     *                   common-format list and full fallback scan still apply).
     * @return array of 1+ DetectionResults, index 0 = best guess - see class
     *         javadoc for how this differs from DroidSignatureVerifier's own
     *         "also matched" tail in the fast path specifically
     */
    public DetectionResult[] detect(File targetFile, String hint) throws Exception {
        DroidSignatureVerifier.FileRegion region = DroidSignatureVerifier.readBoundedRegion(targetFile);
        return detectFromRegion(region, hint);
    }

    /**
     * Same as detect(File, String), but reads from an InputStream instead -
     * identical mark/reset contract to DroidSignatureVerifier.detect(InputStream).
     *
     * @param in   the stream to identify; not closed by this method
     * @param hint see detect(File, String)'s javadoc
     */
    public DetectionResult[] detect(InputStream in, String hint) throws Exception {
        if (in.markSupported()) {
            try {
                in.reset();
            } catch (IOException e) {
                System.out.println("  (reset() failed on the supplied stream, reading from current position: " + e + ")");
            }
        }
        DroidSignatureVerifier.FileRegion region = DroidSignatureVerifier.readBoundedRegion(in);
        return detectFromRegion(region, hint);
    }

    private DetectionResult[] detectFromRegion(DroidSignatureVerifier.FileRegion region, String hint) throws Exception {
        String extension = extractExtension(hint);

        LinkedHashSet<DroidSignatureVerifier.InternalSignatureDef> candidates = new LinkedHashSet<>();
        if (extension != null) {
            List<DroidSignatureVerifier.InternalSignatureDef> extSigs = signaturesByExtension.get(extension);
            if (extSigs != null) candidates.addAll(extSigs);
        }
        candidates.addAll(commonSignatures);

        Set<Integer> checkedSigIds = new HashSet<>();
        List<Integer> matchedSigIds = new ArrayList<>();

        for (DroidSignatureVerifier.InternalSignatureDef sig : candidates) {
            checkedSigIds.add(sig.id);
            if (DroidSignatureVerifier.matchSignature(region, sig)) {
                matchedSigIds.add(sig.id);
            }
        }

        if (matchedSigIds.isEmpty()) {
            // Nothing in the fast-path candidates matched at all - fall back to
            // the full exhaustive scan (identical logic to DroidSignatureVerifier's
            // own detectFromRegion()), the correctness safety net for the long tail.
            return fullScan(region);
        }

        // Chase priority overriders: check ONLY the specific formats that could
        // suppress what we've matched so far, iterating in case of a multi-level
        // priority chain (rare in practice - JAR/ODP overriding ZIP is one level -
        // but handled generally rather than assuming a fixed depth).
        Set<Integer> overridersToCheck = new LinkedHashSet<>();
        for (int sigId : matchedSigIds) {
            for (DroidSignatureVerifier.FileFormatDef fmt : formatsForSignatureId.getOrDefault(sigId, Collections.emptyList())) {
                overridersToCheck.addAll(couldBeOverriddenByFormatIds.getOrDefault(fmt.id, Collections.emptyList()));
            }
        }
        for (int round = 0; round < 5 && !overridersToCheck.isEmpty(); round++) {
            Set<Integer> nextRound = new LinkedHashSet<>();
            for (int formatId : overridersToCheck) {
                DroidSignatureVerifier.FileFormatDef fmt = formatById.get(formatId);
                if (fmt == null) continue;
                for (int sigId : fmt.signatureIds) {
                    if (!checkedSigIds.add(sigId)) continue; // already checked this round or earlier
                    DroidSignatureVerifier.InternalSignatureDef sig = signatureById.get(sigId);
                    if (sig != null && DroidSignatureVerifier.matchSignature(region, sig)) {
                        matchedSigIds.add(sigId);
                        nextRound.addAll(couldBeOverriddenByFormatIds.getOrDefault(formatId, Collections.emptyList()));
                    }
                }
            }
            overridersToCheck = nextRound;
        }

        return resolveAndBuildResults(matchedSigIds);
    }

    /** Full exhaustive scan over every signature - identical logic to
     *  DroidSignatureVerifier's own detectFromRegion(), used only as the
     *  fallback when the fast path finds nothing at all. */
    private DetectionResult[] fullScan(DroidSignatureVerifier.FileRegion region) throws Exception {
        List<Integer> matchedSigIds = new ArrayList<>();
        for (DroidSignatureVerifier.InternalSignatureDef sig : signatures) {
            if (DroidSignatureVerifier.matchSignature(region, sig)) {
                matchedSigIds.add(sig.id);
            }
        }
        if (matchedSigIds.isEmpty()) return new DetectionResult[0];
        return resolveAndBuildResults(matchedSigIds);
    }

    private DetectionResult[] resolveAndBuildResults(List<Integer> matchedSigIds) {
        Set<Integer> matchedSigIdSet = new HashSet<>(matchedSigIds);
        List<DroidSignatureVerifier.FileFormatDef> matchedFormats = new ArrayList<>();
        for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
            for (int sigId : fmt.signatureIds) {
                if (matchedSigIdSet.contains(sigId)) {
                    matchedFormats.add(fmt);
                    break;
                }
            }
        }
        if (matchedFormats.isEmpty()) return new DetectionResult[0];

        List<DroidSignatureVerifier.FileFormatDef> resolved = DroidSignatureVerifier.applyPriorityResolution(matchedFormats);

        List<DroidSignatureVerifier.FileFormatDef> ordered = new ArrayList<>(resolved);
        for (DroidSignatureVerifier.FileFormatDef fmt : matchedFormats) {
            if (!ordered.contains(fmt)) ordered.add(fmt);
        }

        int count = Math.min(10, ordered.size());
        DetectionResult[] results = new DetectionResult[count];
        for (int i = 0; i < count; i++) {
            DroidSignatureVerifier.FileFormatDef fmt = ordered.get(i);
            results[i] = new DetectionResult(fmt.puid, fmt.name, fmt.mimeType, fmt.version);
        }
        return results;
    }
}