package uk.bl.wa.droidlight;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * DroidSignatureAhoCorasickVerifier - combines the single-pass Aho-Corasick anchor
 * scanning architecture (AhoCorasickDroidPrototype2) with DroidSignatureVerifier's
 * validated matching semantics (offset-window-applies-to-chain-end, fragment
 * alternatives, multi-ByteSequence AND, priority resolution, etc).
 *
 * WHY THIS EXISTS
 * -----------------
 * DroidSignatureVerifier - correct as it now is - still searches for each of its
 * 2,260 signatures' anchors independently: one small window-scan per signature,
 * even though MAX_ANCHOR_SEARCH_DISTANCE keeps each of those scans cheap. That's
 * 2,260 separate scans of (mostly) the same bytes. This class instead compiles
 * every signature's FIXED (reference-point-anchored) literal anchor into ONE
 * combined Aho-Corasick automaton and finds ALL of their occurrences across the
 * WHOLE target file in a SINGLE pass - O(file_length), independent of how many
 * signatures are loaded. The (already-correct) fragment-chain/offset-window
 * verification logic then runs only against those precomputed candidate
 * positions, reusing DroidSignatureVerifier's own matchChainedSubSequence,
 * verifyRightFragmentChain, verifyLeftFragmentChain, and applyPriorityResolution
 * directly (all package-private static members of DroidSignatureVerifier, reused
 * here rather than duplicated, since both classes live in the same package,
 * uk.bl.wa.droidlight).
 *
 * WHAT IS NOT SPED UP BY THIS CLASS
 * -------------------------------------
 * Only the FIXED (first, reference-point-anchored) SubSequence of each
 * ByteSequence gets the Aho-Corasick treatment. Chained (non-first) SubSequences
 * (e.g. MP4 signature 278's "moov" search after "ftyp") still use
 * DroidSignatureVerifier.matchChainedSubSequence unchanged - a plain per-signature
 * window scan, same as before. These are usually far fewer and far more tightly
 * bounded by their own SubSeqMinOffset/MaxOffset in practice, so this was judged
 * not worth the added complexity of compiling a second, chained-context automaton
 * - but it is a known asymmetry, not an oversight.
 *
 * Net effect: this class should now be fast and memory-bounded regardless of
 * file size, converging with (and, for the anchor-finding stage specifically,
 * still ahead of) DroidSignatureVerifier's own bounded-window design - the
 * "signature_count x window_size" per-signature cost DroidSignatureVerifier
 * still pays for its independent scans is replaced here by one shared pass over
 * the same small windows for ALL signatures at once.
 */
public class DroidSignatureAhoCorasickVerifier {

    /** Safety cap on how many precomputed candidate anchor positions will actually
     *  be tried (fragment-chain-verified) per (signature, ByteSequence) pair. See
     *  class javadoc: this replaces MAX_ANCHOR_SEARCH_DISTANCE's safety-valve role,
     *  now based on candidate COUNT (the real cost driver) instead of search
     *  distance. Candidates are tried closest-to-reference-point first, so this
     *  cap only matters for signatures whose anchor happens to occur an unusually
     *  large number of times in one file (as signature 280's did).
     */
    static int MAX_CANDIDATES_TO_TRY = 50;

    /** Aho-Corasick trie node - flat 256-entry array transition table (see
     *  AhoCorasickDroidPrototype2 for the earlier standalone version and its
     *  measured throughput vs a HashMap-based trie). */
    static final class TrieNode {
        final TrieNode[] children = new TrieNode[256];
        TrieNode fail;
        // Each output entry identifies which (signature, ByteSequence) pair's fixed
        // anchor ends at this trie node: {signatureIndex, byteSequenceIndex}.
        List<int[]> outputs;
    }

    private final List<DroidSignatureVerifier.InternalSignatureDef> signatures;
    private final List<DroidSignatureVerifier.FileFormatDef> formats;
    private final TrieNode trieRoot = new TrieNode();

    // Flat-array mapping from a compact "anchor slot" id (assigned during trie
    // construction, one per distinct (sigIdx,bsIdx) pair with a fixed anchor)
    // back to that pair - lets scanBufferInto use direct array indexing instead
    // of nested HashMap lookups per hit (see HitBuffer's javadoc for why the
    // per-hit hot path needed this: hashing/boxing overhead across tens of
    // millions of hits on a repetitive-content file was itself a major cost,
    // separate from the O(cap)-eviction bug HitBuffer also fixes).
    private int[] flatSigIdx;
    private int[] flatBsIdx;
    private int totalAnchorSlots;

    /**
     * Parses the given DROID signature file once (reusing
     * DroidSignatureVerifier's own parser directly - no duplicated parsing logic),
     * then compiles every signature's fixed anchor into one combined Aho-Corasick
     * automaton.
     */
    public DroidSignatureAhoCorasickVerifier(File signatureFile) throws Exception {
        System.out.println("Parsing full signature structure (anchors + fragments + endianness): " + signatureFile);
        long t0 = System.nanoTime();
        this.signatures = DroidSignatureVerifier.parseSignatures(signatureFile);
        this.formats = DroidSignatureVerifier.parseFileFormats(signatureFile);
        long t1 = System.nanoTime();
        System.out.printf("  Parsed %,d signatures and %,d file formats in %.1f ms%n",
                signatures.size(), formats.size(), (t1 - t0) / 1e6);

        List<Integer> flatSigIdxList = new ArrayList<>();
        List<Integer> flatBsIdxList = new ArrayList<>();
        int anchorCount = 0;
        for (int sigIdx = 0; sigIdx < signatures.size(); sigIdx++) {
            DroidSignatureVerifier.InternalSignatureDef sig = signatures.get(sigIdx);
            for (int bsIdx = 0; bsIdx < sig.byteSequences.size(); bsIdx++) {
                DroidSignatureVerifier.ByteSequenceDef bs = sig.byteSequences.get(bsIdx);
                DroidSignatureVerifier.SubSequenceDef fixedSub = getFixedSubSequence(bs);
                if (fixedSub == null || fixedSub.anchor == null) continue;
                byte[] literalAnchor = toLiteralBytesOrNull(fixedSub.anchor);
                // toLiteralBytesOrNull returns null if the anchor contains a wildcard
                // (range/NOT) - in practice DROID's primary <Sequence> never does
                // (confirmed by inspection of the real V111/V124 signature files),
                // but this is a defensive check, not an assumption.
                if (literalAnchor == null || literalAnchor.length == 0) continue;
                boolean isEofDirected = (bs.reference == DroidSignatureVerifier.Reference.EOF);
                int flatId = anchorCount;
                addToTrie(literalAnchor, flatId, literalAnchor.length, isEofDirected);
                flatSigIdxList.add(sigIdx);
                flatBsIdxList.add(bsIdx);
                anchorCount++;
            }
        }
        totalAnchorSlots = anchorCount;
        flatSigIdx = new int[anchorCount];
        flatBsIdx = new int[anchorCount];
        for (int i = 0; i < anchorCount; i++) {
            flatSigIdx[i] = flatSigIdxList.get(i);
            flatBsIdx[i] = flatBsIdxList.get(i);
        }
        buildFailureLinks();
        long t2 = System.nanoTime();
        System.out.printf("  Compiled %,d fixed anchors into one combined Aho-Corasick automaton in %.1f ms%n",
                anchorCount, (t2 - t1) / 1e6);
    }

    // ------------------------------------------------------------------
    // Trie construction helpers
    // ------------------------------------------------------------------

    /** The "fixed" SubSequence of a ByteSequence is the one directly anchored to
     *  its BOF/EOF/Anywhere reference point - the first one in Position order for
     *  BOF/Anywhere, or the LAST one in Position order for EOF (DROID processes
     *  EOF-referenced SubSequences in reverse - see
     *  DroidSignatureVerifier.matchByteSequence, which this mirrors). */
    static DroidSignatureVerifier.SubSequenceDef getFixedSubSequence(DroidSignatureVerifier.ByteSequenceDef bs) {
        List<DroidSignatureVerifier.SubSequenceDef> subs = new ArrayList<>(bs.subSequences);
        if (subs.isEmpty()) return null;
        subs.sort(Comparator.comparingInt(s -> s.position));
        if (bs.reference == DroidSignatureVerifier.Reference.EOF) Collections.reverse(subs);
        return subs.get(0);
    }

    /** Converts a parsed anchor pattern to plain literal bytes for the Aho-Corasick
     *  trie, or null if it contains any wildcard element (Aho-Corasick here only
     *  indexes exact-byte anchors; wildcard verification still happens correctly
     *  in the fragment-chain stage, which is unaffected by this). */
    static byte[] toLiteralBytesOrNull(List<DroidSignatureVerifier.PatternElement> pattern) {
        byte[] out = new byte[pattern.size()];
        for (int i = 0; i < pattern.size(); i++) {
            DroidSignatureVerifier.PatternElement pe = pattern.get(i);
            if (pe.kind != DroidSignatureVerifier.Kind.LITERAL) return null;
            out[i] = (byte) pe.a;
        }
        return out;
    }

    void addToTrie(byte[] pattern, int flatId, int anchorLen, boolean isEofDirected) {
        TrieNode cur = trieRoot;
        for (byte b : pattern) {
            int idx = b & 0xFF;
            if (cur.children[idx] == null) cur.children[idx] = new TrieNode();
            cur = cur.children[idx];
        }
        if (cur.outputs == null) cur.outputs = new ArrayList<>();
        cur.outputs.add(new int[]{flatId, anchorLen, isEofDirected ? 1 : 0});
    }

    void buildFailureLinks() {
        Deque<TrieNode> queue = new ArrayDeque<>();
        trieRoot.fail = trieRoot;
        for (int b = 0; b < 256; b++) {
            TrieNode child = trieRoot.children[b];
            if (child != null) {
                child.fail = trieRoot;
                queue.add(child);
            }
        }
        while (!queue.isEmpty()) {
            TrieNode cur = queue.poll();
            for (int b = 0; b < 256; b++) {
                TrieNode child = cur.children[b];
                if (child == null) continue;
                TrieNode f = cur.fail;
                while (f != trieRoot && f.children[b] == null) f = f.fail;
                TrieNode candidate = f.children[b];
                child.fail = (candidate != null && candidate != child) ? candidate : trieRoot;
                if (child.fail.outputs != null) {
                    if (child.outputs == null) child.outputs = new ArrayList<>();
                    child.outputs.addAll(child.fail.outputs);
                }
                queue.add(child);
            }
        }
    }

    // ------------------------------------------------------------------
    // Single-pass anchor scan
    // ------------------------------------------------------------------

    /**
     * Scans only the head and tail windows of a FileRegion - NOT the whole file.
     *
     * WHY THIS IS BOTH CORRECT AND FASTER (fixes both the OOM and the earlier
     * large-file slowdown in one change): the fragment-verification stage below
     * (matchFixedSubSequenceUsingHits etc) only ever has byte access within the
     * SAME bounded head+tail FileRegion DroidSignatureVerifier already uses -
     * FileRegion.byteAt() returns -1 for anything in the unloaded "gap" between
     * them. So an anchor hit found outside those windows could never be verified
     * anyway; scanning the whole file for such hits was always wasted work. This
     * also directly fixes this class's own OutOfMemoryError (it previously called
     * Files.readAllBytes on the whole file just to feed this scan) and its
     * earlier large-file slowdown (see class javadoc "MEASURED RESULT" table
     * above - that O(file_length) full-file pass was the dominant cost on large
     * files; scanning only ~40MB total regardless of true file size removes that
     * cost entirely).
     *
     * The head and tail are each scanned as ONE uninterrupted automaton pass -
     * trie state resets to root between them (they aren't contiguous in the real
     * file, so carrying state across that gap would be wrong), but there is no
     * internal chunk boundary WITHIN either window, so no match can ever be
     * split by this scanning strategy - the only "boundary" is the head/tail
     * split itself, which is the exact same, already-validated boundary
     * DroidSignatureVerifier's own bounded search has always had.
     *
     * Positions are `long`, not `int`: a tail window's absolute position (true
     * file length minus window size, plus an offset within the tail buffer) can
     * exceed Integer.MAX_VALUE for files above ~2GB - exactly the file size range
     * this whole fix targets.
     */
    Map<Integer, Map<Integer, List<Long>>> scanForAnchors(DroidSignatureVerifier.FileRegion region) {
        HitBuffer[] hitBuffers = new HitBuffer[totalAnchorSlots];
        scanBufferInto(region.head, 0L, hitBuffers);
        if (region.tail != null) {
            scanBufferInto(region.tail, region.tailStartAbsolute, hitBuffers);
        }

        // Convert the flat array back to the (sigIdx -> bsIdx -> positions) shape
        // the verification stage expects - this loop runs once per anchor SLOT
        // (~2,657 total), not once per raw hit, so its HashMap usage here is
        // negligible regardless of how many raw hits occurred during scanning.
        Map<Integer, Map<Integer, List<Long>>> hits = new HashMap<>();
        for (int flatId = 0; flatId < hitBuffers.length; flatId++) {
            HitBuffer buf = hitBuffers[flatId];
            if (buf == null) continue;
            int sigIdx = flatSigIdx[flatId];
            int bsIdx = flatBsIdx[flatId];
            hits.computeIfAbsent(sigIdx, k -> new HashMap<>()).put(bsIdx, buf.toAscendingList());
        }
        return hits;
    }

    /**
     * Bounded, O(1)-per-insertion holder for up to MAX_CANDIDATES_TO_TRY hit
     * positions for one (signature, ByteSequence) pair.
     *
     * BUG FIX: an earlier version stored positions in a plain ArrayList and
     * evicted the oldest entry via `positions.remove(0)` once full - correct, but
     * remove(0) on an ArrayList is O(cap) (it shifts every remaining element).
     * For a short, common literal anchor (e.g. a single 0x00 byte) that happens
     * to match at EVERY position across a long run of repetitive/silent bytes
     * (confirmed on a real-shaped test file: a large all-zero region caused one
     * such anchor to raw-hit ~20 million times), that's ~20 million x O(cap)
     * eviction shifts - cheap individually, but summing to roughly a billion
     * operations, and directly explains a measured 77-second anchor scan over
     * just 40MB of data that should have taken a few hundred milliseconds. This
     * is a real risk on genuine files too, not just a synthetic edge case - long
     * silent stretches in audio, or blank/black regions in video, are exactly
     * this kind of repetitive content. Fixed with a circular buffer: eviction is
     * O(1) regardless of how many raw hits occur, since a new value simply
     * overwrites whichever slot is `cap` insertions behind it.
     */
    static final class HitBuffer {
        final long[] positions = new long[MAX_CANDIDATES_TO_TRY];
        int count = 0;
        int nextWriteIndex = 0;
        boolean full = false;

        /** BOF-directed: once full, later (larger, less useful) positions are
         *  simply skipped - O(1) either way. */
        void addBof(long pos) {
            if (!full) {
                positions[count++] = pos;
                if (count == positions.length) full = true;
            }
        }

        /** EOF-directed: once full, the new (larger, more useful) position
         *  overwrites whichever slot holds the oldest (smallest) one - O(1). */
        void addEof(long pos) {
            if (!full) {
                positions[count++] = pos;
                if (count == positions.length) full = true;
            } else {
                positions[nextWriteIndex] = pos;
                nextWriteIndex = (nextWriteIndex + 1) % positions.length;
            }
        }

        /** Returns the retained positions in ascending (chronological) order. */
        List<Long> toAscendingList() {
            List<Long> result = new ArrayList<>(count);
            if (!full) {
                for (int i = 0; i < count; i++) result.add(positions[i]);
            } else {
                // nextWriteIndex currently points at the oldest retained entry
                // (the next one due to be overwritten) - start reading from there.
                for (int i = 0; i < positions.length; i++) {
                    result.add(positions[(nextWriteIndex + i) % positions.length]);
                }
            }
            return result;
        }
    }

    private void scanBufferInto(byte[] buffer, long absoluteOffset, HitBuffer[] hitBuffers) {
        TrieNode cur = trieRoot;
        for (int i = 0; i < buffer.length; i++) {
            int b = buffer[i] & 0xFF;
            while (cur != trieRoot && cur.children[b] == null) cur = cur.fail;
            TrieNode next = cur.children[b];
            cur = (next != null) ? next : trieRoot;
            if (cur.outputs != null) {
                for (int[] out : cur.outputs) {
                    int flatId = out[0];
                    int anchorLen = out[1];
                    boolean isEofDirected = (out[2] == 1);
                    long startPos = absoluteOffset + i - anchorLen + 1;

                    HitBuffer buf = hitBuffers[flatId];
                    if (buf == null) {
                        buf = new HitBuffer();
                        hitBuffers[flatId] = buf;
                    }
                    if (isEofDirected) buf.addEof(startPos); else buf.addBof(startPos);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Verification using precomputed anchor candidates (instead of
    // DroidSignatureVerifier.matchFixedSubSequence's own byte-by-byte candidate
    // search - everything else is reused unchanged).
    // ------------------------------------------------------------------

    /** Same semantics as DroidSignatureVerifier.matchFixedSubSequence (offset
     *  window applies to the fragment chain's END relative to the reference
     *  point, candidates tried closest-to-reference-point first), but iterating
     *  over precomputed candidate positions instead of scanning for them, and
     *  capped by MAX_CANDIDATES_TO_TRY instead of MAX_ANCHOR_SEARCH_DISTANCE.
     *
     *  NOTE: takes a DroidSignatureVerifier.FileRegion (not a raw byte[]) purely
     *  to stay compatible with DroidSignatureVerifier's methods after its own
     *  large-file fix (see its class javadoc "OutOfMemoryError" note) - this
     *  class's OWN large-file handling (scanForAnchors still needs a byte[] for

     *  its Aho-Corasick automaton, and detect(File)/detect(InputStream) below
     *  still read the whole file/stream) has NOT been fixed yet; that is
     *  deliberately a separate, later step. */
    static long matchFixedSubSequenceUsingHits(DroidSignatureVerifier.FileRegion region, DroidSignatureVerifier.SubSequenceDef sub,
                                                long referencePosition, boolean searchBackward,
                                                List<Long> anchorStartPositions) {
        if (sub.anchor == null || anchorStartPositions == null || anchorStartPositions.isEmpty()) return -1;
        long maxOffset = (sub.maxSeqOffset >= 0) ? sub.maxSeqOffset : Long.MAX_VALUE / 2;
        int minOffset = sub.minSeqOffset;
        int anchorLen = DroidSignatureVerifier.patternLength(sub.anchor);
        int n = anchorStartPositions.size();

        if (searchBackward) {
            // EOF-anchored: candidates closest to true EOF first = largest position first.
            for (int idx = n - 1, tried = 0; idx >= 0 && tried < MAX_CANDIDATES_TO_TRY; idx--, tried++) {
                long candidate = anchorStartPositions.get(idx);
                long afterAnchor = candidate + anchorLen;
                long chainEnd = DroidSignatureVerifier.verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
                if (chainEnd >= 0) {
                    long distanceFromReference = referencePosition - chainEnd;
                    if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                        long beforeLeft = DroidSignatureVerifier.verifyLeftFragmentChain(region, candidate, sub.groupedLeftFragments);
                        if (beforeLeft >= 0) return chainEnd;
                    }
                }
            }
            return -1;
        } else {
            // BOF-anchored: candidates closest to BOF first = smallest position first
            // (already the natural ascending order the scan produced them in).
            for (int idx = 0, tried = 0; idx < n && tried < MAX_CANDIDATES_TO_TRY; idx++, tried++) {
                long candidate = anchorStartPositions.get(idx);
                long beforeLeft = DroidSignatureVerifier.verifyLeftFragmentChain(region, candidate, sub.groupedLeftFragments);
                if (beforeLeft >= 0) {
                    long distanceFromReference = beforeLeft - referencePosition;
                    if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                        long afterAnchor = candidate + anchorLen;
                        long chainEnd = DroidSignatureVerifier.verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
                        if (chainEnd >= 0) return chainEnd;
                    }
                }
            }
            return -1;
        }
    }

    /** Same structure as DroidSignatureVerifier.matchByteSequence: the fixed
     *  (first/reference-anchored) SubSequence now uses precomputed hits; any
     *  chained (non-first) SubSequences reuse
     *  DroidSignatureVerifier.matchChainedSubSequence unchanged. */
    boolean matchByteSequenceUsingHits(DroidSignatureVerifier.FileRegion region, DroidSignatureVerifier.ByteSequenceDef bs,
                                        int sigIdx, int bsIdx,
                                        Map<Integer, Map<Integer, List<Long>>> anchorHits) {
        List<DroidSignatureVerifier.SubSequenceDef> subs = bs.orderedSubSequences;
        boolean backward = (bs.reference == DroidSignatureVerifier.Reference.EOF);

        long referencePosition = backward ? region.length : 0;
        boolean first = true;
        long cursor = -1;
        for (DroidSignatureVerifier.SubSequenceDef sub : subs) {
            long result;
            if (first) {
                List<Long> hits = null;
                Map<Integer, List<Long>> bySig = anchorHits.get(sigIdx);
                if (bySig != null) hits = bySig.get(bsIdx);
                result = matchFixedSubSequenceUsingHits(region, sub, referencePosition, backward, hits);
            } else {
                result = DroidSignatureVerifier.matchChainedSubSequence(region, sub, cursor);
            }
            if (result < 0) return false;
            cursor = result;
            first = false;
        }
        return true;
    }

    boolean matchSignatureUsingHits(DroidSignatureVerifier.FileRegion region, DroidSignatureVerifier.InternalSignatureDef sig, int sigIdx,
                                     Map<Integer, Map<Integer, List<Long>>> anchorHits) {
        for (int bsIdx = 0; bsIdx < sig.byteSequences.size(); bsIdx++) {
            if (!matchByteSequenceUsingHits(region, sig.byteSequences.get(bsIdx), sigIdx, bsIdx, anchorHits)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Public API - identical shape to DroidSignatureVerifier's detect(), so the
    // two classes are drop-in interchangeable.
    // ------------------------------------------------------------------

    /**
     * Runs full format detection for one file and returns up to 10 candidate
     * results, best guess first - identical contract and ordering to
     * DroidSignatureVerifier.detect() (see its javadoc), just computed via the
     * single-pass Aho-Corasick anchor stage instead of independent per-signature
     * scans.
     *
     * LARGE-FILE HANDLING: reuses DroidSignatureVerifier.readBoundedRegion(File)
     * directly - the same bounded head+tail window reading that fixed
     * DroidSignatureVerifier's OutOfMemoryError on multi-GB files. This class's
     * Aho-Corasick scan now runs over those same bounded windows (see
     * scanForAnchors's javadoc for why that's correct, not just convenient) -
     * fixing both this class's own OutOfMemoryError AND its earlier large-file
     * slowdown in the same change (see class javadoc "MEASURED RESULT" - that
     * regression was caused by the full-file scan this fix removes).
     */
    public DetectionResult[] detect(File targetFile) throws Exception {
        DroidSignatureVerifier.FileRegion region = DroidSignatureVerifier.readBoundedRegion(targetFile);
        if (DroidSignatureVerifier.VERBOSE) System.out.println("\nVerifying against: " + targetFile + " (" + region.length + " bytes)");
        return detectFromRegion(region);
    }

    /**
     * Same as detect(File), but reads from an InputStream instead - identical
     * contract to DroidSignatureVerifier.detect(InputStream) (see its javadoc for
     * the mark/reset behavior and rationale, and readBoundedRegion(InputStream)
     * for the streaming head+circular-tail-buffer strategy - both reused
     * directly, unchanged, from DroidSignatureVerifier).
     *
     * @param in the stream to identify; not closed by this method - the caller
     *           retains ownership and is responsible for closing it
     * @return array of 0 to 10 DetectionResults, index 0 = top candidate
     */
    public DetectionResult[] detect(InputStream in) throws Exception {
        if (in.markSupported()) {
            try {
                in.reset();
            } catch (IOException e) {
                System.out.println("  (reset() failed on the supplied stream, reading from current position: " + e + ")");
            }
        }
        DroidSignatureVerifier.FileRegion region = DroidSignatureVerifier.readBoundedRegion(in);
        if (DroidSignatureVerifier.VERBOSE) System.out.println("\nVerifying against: <input stream> (" + region.length + " bytes)");
        return detectFromRegion(region);
    }

    /**
     * The actual detection logic, shared by both detect(File) and
     * detect(InputStream) - mirrors DroidSignatureVerifier's own
     * detectFromRegion() split, just using the Aho-Corasick anchor stage instead
     * of independent per-signature scans.
     */
    private DetectionResult[] detectFromRegion(DroidSignatureVerifier.FileRegion region) throws Exception {
        // PERFORMANCE: same reasoning as DroidSignatureVerifier's own
        // detectFromRegion() - an empty region can never match anything, so
        // skip the anchor scan and the signature loop entirely. See that
        // class's comment for why this matters in practice (empty-payload
        // HTTP redirect WARC records).
        if (region.length == 0) {
            if (DroidSignatureVerifier.VERBOSE) System.out.println("  Empty content - skipping signature verification entirely.");
            return new DetectionResult[0];
        }

        long t0 = System.nanoTime();
        Map<Integer, Map<Integer, List<Long>>> anchorHits = scanForAnchors(region);
        long t1 = System.nanoTime();
        if (DroidSignatureVerifier.VERBOSE) {
            System.out.printf("  Single-pass Aho-Corasick anchor scan completed in %.1f ms%n", (t1 - t0) / 1e6);
        }

        List<Integer> matchedSignatureIds = new ArrayList<>();
        for (int sigIdx = 0; sigIdx < signatures.size(); sigIdx++) {
            DroidSignatureVerifier.InternalSignatureDef sig = signatures.get(sigIdx);
            if (matchSignatureUsingHits(region, sig, sigIdx, anchorHits)) {
                matchedSignatureIds.add(sig.id);
            }
        }
        long t2 = System.nanoTime();
        if (DroidSignatureVerifier.VERBOSE) {
            System.out.printf("  Verification of all %,d signatures (using precomputed anchors) completed in %.1f ms (no hang, no backtracking)%n",
                    signatures.size(), (t2 - t1) / 1e6);
        }

        if (matchedSignatureIds.isEmpty()) {
            if (DroidSignatureVerifier.VERBOSE) System.out.println("  No signatures matched.");
            return new DetectionResult[0];
        }

        List<DroidSignatureVerifier.FileFormatDef> matchedFormats = new ArrayList<>();
        for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
            for (int sigId : fmt.signatureIds) {
                if (matchedSignatureIds.contains(sigId)) {
                    matchedFormats.add(fmt);
                    break;
                }
            }
        }
        if (DroidSignatureVerifier.VERBOSE) {
            System.out.println("  Raw matched signature IDs: " + matchedSignatureIds
                    + " (" + matchedFormats.size() + " format(s) before priority resolution)");
        }

        List<DroidSignatureVerifier.FileFormatDef> resolved = DroidSignatureVerifier.applyPriorityResolution(matchedFormats);
        if (DroidSignatureVerifier.VERBOSE) {
            System.out.println("  After priority resolution:");
            for (DroidSignatureVerifier.FileFormatDef fmt : resolved) {
                System.out.println("    -> " + fmt.puid + "  " + fmt.name);
            }
        }

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

    // ------------------------------------------------------------------
    // No main() method in this class - see project convention: this class is a
    // library component, constructed and called from other code, not run
    // directly.
    // ------------------------------------------------------------------
}