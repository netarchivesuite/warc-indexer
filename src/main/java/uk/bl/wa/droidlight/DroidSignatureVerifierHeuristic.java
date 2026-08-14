package uk.bl.wa.droidlight;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.util.*;

/**
 * DroidSignatureVerifierHeuristic - a fundamentally different matching STRATEGY
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
     * Search distance used ONLY by rescanLongAnchorSignatures() below - large
     * enough to comfortably cover every known real case requiring more than
     * the fast default (DroidSignatureVerifier.MAX_ANCHOR_SEARCH_DISTANCE,
     * 3000): certain JPEGs needing up to ~65,536 to reach an EOF trailer past
     * a large embedded thumbnail (confirmed on a real production file), and
     * DMG disk images needing up to ~136,004. Confirmed working in production
     * for the JPEG case specifically. Deliberately NOT used for the normal
     * fast scan - only for the small, targeted signature set below, where
     * the extra search cost is bounded and rare rather than paid on every file.
     */
    public static long LONG_ANCHOR_SEARCH_DISTANCE = 150_000;

    /**
     * Signatures that the fast default scan (DroidSignatureVerifier.
     * MAX_ANCHOR_SEARCH_DISTANCE, 3000) can NEVER find, even for genuinely
     * matching content - because at least one ByteSequence's fixed (first)
     * SubSequence declares a SubSeqMaxOffset larger than 3000, or leaves it
     * unbounded entirely, meaning the real anchor could legitimately sit
     * further away than the fast scan's search window ever reaches.
     *
     * Precomputed ONCE at construction time (see the constructor), not per
     * file - this is what makes rescanLongAnchorSignatures() cheap: it only
     * ever needs to check this small, targeted list, not all 2,258 signatures
     * again, since everything else was already conclusively ruled out (or
     * in) by the fast scan at distance 3000.
     */
    private final List<DroidSignatureVerifier.InternalSignatureDef> longAnchorSignatures = new ArrayList<>();

    /**
     * Parses the given DROID signature file - reusing DroidSignatureVerifier's
     * own parser directly for the core signature/format data (nothing about
     * DroidSignatureVerifier itself is modified) - then builds the extra
     * indexes this class's ordering strategy needs (extension lookup, priority
     * reverse-index, common-format list).
     */
    public DroidSignatureVerifierHeuristic(File signatureFile) throws Exception {
        this(new FileInputStream(signatureFile), signatureFile.toString());
    }

    /**
     * Same as the File constructor, but reads from an already-open InputStream
     * instead - e.g. a classpath resource stream
     * (getClass().getClassLoader().getResourceAsStream("DROID_SignatureFile_V124.xml")),
     * which is what you need once the signature file ships as a Maven resource
     * packed inside a jar: a plain java.io.File can't represent a jar entry at
     * all, but the classloader resolves the same resource name correctly
     * whether it's running from an IDE, from Maven's test phase (src/main/
     * resources is copied to target/classes - a real directory - before tests
     * run), or from the packaged jar itself.
     *
     * Reads the ENTIRE stream into memory once (a DROID signature file is only
     * a few MB), since the constructor needs to parse the same underlying XML
     * three separate times (signatures, formats, extensions - the last of
     * which isn't captured by DroidSignatureVerifier's own shared parser) and
     * an InputStream can only be consumed once. Does not close the given
     * stream - the caller retains ownership, e.g. to close a try-with-resources
     * classpath resource stream themselves.
     */
    public DroidSignatureVerifierHeuristic(InputStream signatureStream) throws Exception {
        this(signatureStream, "<input stream>");
    }

    private DroidSignatureVerifierHeuristic(InputStream signatureStream, String sourceDescription) throws Exception {
        System.out.println("Parsing full signature structure (anchors + fragments + endianness): " + sourceDescription);
        long t0 = System.nanoTime();

        byte[] xmlBytes = signatureStream.readAllBytes();
        this.signatures = DroidSignatureVerifier.parseSignatures(new ByteArrayInputStream(xmlBytes));
        this.formats = DroidSignatureVerifier.parseFileFormats(new ByteArrayInputStream(xmlBytes));
        DroidSignatureVerifier.precomputeOrdering(signatures); // essential - see its own javadoc
        long t1 = System.nanoTime();
        System.out.printf("  Parsed %,d signatures and %,d file formats in %.1f ms%n",
                signatures.size(), formats.size(), (t1 - t0) / 1e6);

        // Signatures the fast default scan (MAX_ANCHOR_SEARCH_DISTANCE, 3000)
        // can NEVER find even for genuinely matching content - because at
        // least one ByteSequence's fixed (first) SubSequence declares a
        // SubSeqMaxOffset larger than 3000, or leaves it unbounded entirely -
        // see longAnchorSignatures's own javadoc for the full rationale.
        for (DroidSignatureVerifier.InternalSignatureDef sig : signatures) {
            boolean qualifies = false;
            for (DroidSignatureVerifier.ByteSequenceDef bs : sig.byteSequences) {
                if (bs.orderedSubSequences == null || bs.orderedSubSequences.isEmpty()) continue;
                DroidSignatureVerifier.SubSequenceDef fixedSub = bs.orderedSubSequences.get(0);
                if (fixedSub.maxSeqOffset < 0 || fixedSub.maxSeqOffset > DroidSignatureVerifier.MAX_ANCHOR_SEARCH_DISTANCE) {
                    qualifies = true;
                    break;
                }
            }
            if (qualifies) longAnchorSignatures.add(sig);
        }
        System.out.printf("  %,d signature(s) need more than the fast default distance (%,d) - see getLongAnchorSignatureNames()%n",
                longAnchorSignatures.size(), DroidSignatureVerifier.MAX_ANCHOR_SEARCH_DISTANCE);

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
        Map<Integer, List<String>> extensionsByFormatId = parseExtensions(new ByteArrayInputStream(xmlBytes));
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
     *  data) rather than modifying that shared method. Takes an already-open
     *  InputStream (doesn't close it) rather than a File, since the
     *  InputStream-based constructor needs to parse the same underlying bytes
     *  three separate times (signatures, formats, extensions) from a single
     *  buffered-in-memory copy - see the InputStream constructor's javadoc. */
    private static Map<Integer, List<String>> parseExtensions(InputStream in) throws Exception {
        Map<Integer, List<String>> result = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        {
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
    /** Same extension-extraction semantics as FallbackFormatDetector.getExtension()
     *  - kept as an independent local copy rather than a cross-class dependency,
     *  same reasoning as this class's other self-contained parsing.
     *
     *  BUG FIX: previously didn't strip a URL query string or fragment before
     *  looking for the extension - see FallbackFormatDetector.getExtension()'s
     *  javadoc for the real production case that surfaced this (a common,
     *  broad issue, not a rare edge case - cache-busting query parameters
     *  like "?v=..." are everywhere on the modern web). */
    static String extractExtension(String hint) {
        if (hint == null) return null;
        int lastSlash = Math.max(hint.lastIndexOf('/'), hint.lastIndexOf('\\'));
        String name = (lastSlash >= 0) ? hint.substring(lastSlash + 1) : hint;

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
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * @return the number of DROID signatures loaded from the signature file
     *         (~2,258 for a typical current PRONOM release) - the total count
     *         this class's fast-path/fallback strategy is choosing among, not
     *         just the ones actually checked for any particular file.
     */
    public int getSignatureCount() {
        return signatures.size();
    }

    /**
     * A simple, one-line label per loaded signature, in the same order as
     * getSignatureCount() reports - e.g. "1487: Hierarchical File System
     * (fmt/1105)". A signature has no name of its own in the DROID data model
     * (only the FileFormat(s) that reference it do); most signatures belong to
     * exactly one format, so this is usually unambiguous. The rare signature
     * referenced by more than one format lists all of them, comma-separated;
     * the rarer still signature referenced by none at all (present in the
     * signature file but not wired to any FileFormat entry) is labeled as such
     * rather than silently omitted, so the array length always matches
     * getSignatureCount().
     *
     * @return one label per loaded signature - never null, never contains null
     *         entries
     */
    public String[] getSignatureNames() {
        String[] names = new String[signatures.size()];
        for (int i = 0; i < signatures.size(); i++) {
            DroidSignatureVerifier.InternalSignatureDef sig = signatures.get(i);
            List<DroidSignatureVerifier.FileFormatDef> owners = formatsForSignatureId.get(sig.id);
            String label;
            if (owners == null || owners.isEmpty()) {
                label = "(no format references this signature)";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < owners.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(owners.get(j).name).append(" (").append(owners.get(j).puid).append(")");
                }
                label = sb.toString();
            }
            names[i] = sig.id + ": " + label;
        }
        return names;
    }

    /**
     * @return the number of signatures that need more than the fast default
     *         search distance to ever be found - see longAnchorSignatures's
     *         javadoc. This is the exact set rescanLongAnchorSignatures()
     *         checks, and only that set - not all loaded signatures.
     */
    public int getLongAnchorSignatureCount() {
        return longAnchorSignatures.size();
    }

    /**
     * A simple, one-line label per long-anchor signature (see
     * longAnchorSignatures's javadoc), in the same "id: format name (PUID)"
     * format as getSignatureNames() - scoped to just this small subset rather
     * than all loaded signatures.
     *
     * @return one label per long-anchor signature - never null, never
     *         contains null entries
     */
    public String[] getLongAnchorSignatureNames() {
        String[] names = new String[longAnchorSignatures.size()];
        for (int i = 0; i < longAnchorSignatures.size(); i++) {
            DroidSignatureVerifier.InternalSignatureDef sig = longAnchorSignatures.get(i);
            List<DroidSignatureVerifier.FileFormatDef> owners = formatsForSignatureId.get(sig.id);
            String label;
            if (owners == null || owners.isEmpty()) {
                label = "(no format references this signature)";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < owners.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(owners.get(j).name).append(" (").append(owners.get(j).puid).append(")");
                }
                label = sb.toString();
            }
            names[i] = sig.id + ": " + label;
        }
        return names;
    }

    /**
     * Re-scans ONLY the small set of signatures the fast default scan can
     * never find (see longAnchorSignatures's javadoc), using a much larger
     * search distance (LONG_ANCHOR_SEARCH_DISTANCE, 150,000 by default).
     * Intended to be called AFTER a normal detect() call returns empty, as a
     * deliberate second, rare fallback tier - not as a replacement for the
     * fast scan, and not run automatically by detect() itself.
     *
     * THREAD SAFETY: unlike naively mutating a shared
     * MAX_ANCHOR_SEARCH_DISTANCE field around a normal detect() call (unsafe
     * under concurrent use - e.g. a 48-thread WARC-processing pipeline), the
     * larger distance is passed as an explicit parameter all the way down to
     * the actual matching loop (see DroidSignatureVerifier.matchSignature's
     * parameterized overload). Nothing shared or mutated - safe to call
     * concurrently from many threads with zero synchronization and zero
     * contention with the normal fast path, which is completely unaffected
     * either way.
     *
     * @param targetFile the file to identify
     * @return array of 0+ DetectionResults from the long-anchor signature set
     *         only - empty if none of them match either
     */
    public DetectionResult[] rescanLongAnchorSignatures(File targetFile) throws Exception {
        DroidSignatureVerifier.FileRegion region = DroidSignatureVerifier.readBoundedRegion(targetFile);
        return rescanLongAnchorSignaturesFromRegion(region);
    }

    /**
     * Same as rescanLongAnchorSignatures(File), but reads from an InputStream
     * instead - identical mark/reset contract to detect(InputStream).
     *
     * @param in the stream to identify; not closed by this method
     */
    public DetectionResult[] rescanLongAnchorSignatures(InputStream in) throws Exception {
        if (in.markSupported()) {
            try {
                in.reset();
            } catch (IOException e) {
                System.out.println("  (reset() failed on the supplied stream, reading from current position: " + e + ")");
            }
        }
        DroidSignatureVerifier.FileRegion region = DroidSignatureVerifier.readBoundedRegion(in);
        return rescanLongAnchorSignaturesFromRegion(region);
    }

    private DetectionResult[] rescanLongAnchorSignaturesFromRegion(DroidSignatureVerifier.FileRegion region) throws Exception {
        if (region.length == 0) return new DetectionResult[0]; // same reasoning as detectFromRegion's own empty-content check

        List<Integer> matchedSigIds = new ArrayList<>();
        for (DroidSignatureVerifier.InternalSignatureDef sig : longAnchorSignatures) {
            if (DroidSignatureVerifier.matchSignature(region, sig, LONG_ANCHOR_SEARCH_DISTANCE)) {
                matchedSigIds.add(sig.id);
            }
        }
        if (matchedSigIds.isEmpty()) return new DetectionResult[0];
        return resolveAndBuildResults(matchedSigIds);
    }

    /**
     * Same as detect(File, String), but with no hint at all - relies entirely
     * on the common-format list and, if that finds nothing, the full fallback
     * scan. Useful when no filename/URL is available to hint from, or simply
     * for a simpler call site when you don't have one handy.
     *
     * @param targetFile the file to identify
     * @return array of 1+ DetectionResults, index 0 = best guess
     */
    public DetectionResult[] detect(File targetFile) throws Exception {
        return detect(targetFile, null);
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
     * Same as detect(InputStream, String), but with no hint - see
     * detect(File)'s javadoc for when this is useful.
     *
     * @param in the stream to identify; not closed by this method
     */
    public DetectionResult[] detect(InputStream in) throws Exception {
        return detect(in, null);
    }

    /**
     * Same as detect(File, String), but reads from an InputStream instead -
     * identical mark/reset contract to DroidSignatureVerifier.detect(InputStream).
     *
     * @param in   the stream to identify; not closed by this method
     * @param hint see detect(File, String)'s javadoc
     */
    public DetectionResult[] detect(InputStream in, String hint) throws Exception {
        System.out.println("2");
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
        // PERFORMANCE: same reasoning as DroidSignatureVerifier's own
        // detectFromRegion() - an empty region can never match anything. Worth
        // special-casing here specifically: without this, an empty stream would
        // waste time on the fast-path candidates AND THEN still fall through to
        // the full fallback scan (since nothing would match either way) - the
        // worst case of both tiers for no benefit at all.
        if (region.length == 0) {
            if (DroidSignatureVerifier.VERBOSE) System.out.println("  Empty content - skipping signature verification entirely.");
            return new DetectionResult[0];
        }

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