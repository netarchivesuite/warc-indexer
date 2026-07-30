package uk.bl.wa.droidlight;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * DroidSignatureVerifier - a readability-first implementation of DROID's binary
 * signature matching, correcting several gaps and one genuine misunderstanding found
 * through direct testing against real files AND against DROID's own real source
 * (uk.gov.nationalarchives.droid.core.signature.droid6.SubSequence / ByteSequence,
 * fetched from github.com/digital-preservation/droid, develop branch).
 *
 * GAPS IMPLEMENTED (relative to the earlier anchor-only Aho-Corasick prototypes):
 *   1. Wildcard fragment verification ([xx:yy] ranges, [!xx] single-byte negation,
 *      and [!xxxxxx...] multi-byte negation inside RightFragment/LeftFragment).
 *   2. Endianness: INVESTIGATED, found to be a no-op in the working interpretation -
 *      see the note on reversed() below. Real semantics still unresolved.
 *   3. Multi-ByteSequence AND logic (every ByteSequence under a signature must match).
 *   4. Multi-SubSequence AND-chaining within one ByteSequence (fixed a real bug: an
 *      earlier version of this class treated multiple SubSequences as OR, causing
 *      false positives - e.g. a real ODP file matching both JAR and SIARD, since all
 *      ZIP-based formats share the same first SubSequence).
 *
 * NOT IMPLEMENTED:
 *   - Container signatures (ZIP/OLE2 internal-file inspection - a wholly separate
 *     mechanism DROID uses for ODF/OOXML formats; found via a real ODP test that
 *     correctly produces no InternalSignatureCollection match).
 *   - DROID's maxBytesToScan cap and its multi-alternative fragment-option exploration
 *     (bytePosForRightFragments/bytePosForLeftFragments in the real source return
 *     arrays of candidate positions, not a single greedy match - see below).
 *
 * *** THE KEY CORRECTION IN THIS VERSION (found by reading DROID's actual source) ***
 * ------------------------------------------------------------------------------------
 * An earlier version of this class assumed a SubSequence's MinSeqOffset/MaxSeqOffset
 * bounded where the ANCHOR ITSELF must be found, relative to the ByteSequence's
 * reference point (e.g. "the anchor must be within the last 1795 bytes of the file"
 * for an EOFoffset ByteSequence). Testing against a real 180MB MP3 proved this
 * couldn't be right: a real signature (ID 266, MPEG Audio Layer 3) requires 6 chained
 * RightFragments after the anchor, and 6 fragments' worth of real audio-frame spacing
 * (several KB) cannot possibly fit inside a ~1795-byte-wide window near true EOF -
 * yet real DROID correctly identifies such files.
 *
 * Reading DROID's real SubSequence.findSequenceFromPosition() (droid6 package)
 * confirmed the actual semantics: MinSeqOffset/MaxSeqOffset bound where the FURTHEST
 * FRAGMENT IN THE CHAIN ends up, relative to the reference point - NOT where the
 * anchor starts. The anchor itself can be found anywhere further back in the file;
 * what matters is that the chain's endpoint (nearest the reference point) lands
 * within the allowed offset window - e.g. "the audio frames should stop within 47 to
 * 1795 bytes of true EOF, allowing room for a trailing ID3v1/APEv2 tag or similar".
 *
 * Concretely, from the real source (backward/EOF-search branch):
 *   if ((furthestFragmentPos) <= (referencePos - minSeqOffset)) {
 *       invalid = nearestFragmentPos < (referencePos - maxSeqOffset) && recheck(...);
 *   }
 * i.e. the LAST (nearest-to-reference) fragment's position must fall within
 * [referencePos - maxSeqOffset, referencePos - minSeqOffset].
 *
 * Because of this, the anchor search itself must be able to try MULTIPLE candidate
 * positions - starting close to the reference point and working outward/backward -
 * until one produces a fragment chain whose endpoint satisfies that window. DROID's
 * real code does this via its own backtracking search; this class does a simpler
 * (still readability-first) version: it tries anchor candidates one at a time,
 * closest-to-reference first, and for each candidate does a GREEDY (first-match-wins,
 * not exhaustive) fragment chain verification - only backtracking over ANCHOR
 * candidates, not over every possible fragment match. This is deliberately less
 * thorough than DROID's real multi-alternative fragment exploration (see
 * bytePosForRightFragments in the real source, which returns arrays of candidate
 * positions and is explicitly commented by DROID's own authors as
 * "CHECKSTYLE:OFF - way, way, way too complex") - but it is what actually fixes the
 * combinatorial hang (see DROID issue #1663): backtracking over anchor candidates
 * alone is linear-ish in file size, not exponential in fragment-chain depth.
 *
 * This correction, plus the earlier ones, has been validated against:
 *   - A real MP3 with a large ID3v2 tag -> correctly identified as fmt/134.
 *   - A synthetic GIF89a file (multi-ByteSequence AND) -> correctly fmt/4.
 *   - A real ODP file -> correctly finds x-fmt/263 (ZIP) but not fmt/1754 (ODF,
 *     which needs container signatures - out of scope, confirmed via real DROID).
 *   - The original hang-triggering WebP file -> no hang, no false match.
 *   - A real 180MB MP3 (the file that motivated this rewrite) -> see below.
 *
 * PERFORMANCE: still not a goal of this version - naive O(window) scans throughout.
 * Confirmed slower than real DROID on large files. Deferred, as agreed.
 *
 * KNOWN LIMITATION - RESIDUAL FALSE POSITIVES: MOSTLY RESOLVED (was a real bug)
 * ------------------------------------------------------------------------------
 * An earlier checkpoint of this class reported false positives (e.g. a real MP3
 * file also raw-matching signature 10, TIFF, and signature 493, MPEG Layer I,
 * alongside the correct signature 266). Investigating signature 10 specifically
 * found this was NOT DROID being more rigorous than this class - it was a genuine,
 * fixable BUG here: matchFixedSubSequence's BOF/forward-direction offset check had
 * a shortcut ("if there are no LeftFragments, skip the offset validity check
 * entirely") that let a fragment-less signature's anchor match ANYWHERE within
 * MAX_ANCHOR_SEARCH_DISTANCE, instead of enforcing its actual SubSeqMinOffset/
 * SubSeqMaxOffset window. Concretely: signature 10 requires "4D4D002A" at EXACTLY
 * byte offset 0 (no fragments) - but those 4 bytes happened to occur, by pure
 * coincidence, at offset 339 inside a real MP3's ID3v2 tag, and the buggy shortcut
 * accepted it as a match anywhere in the search window rather than only at offset 0.
 * Fixed by always validating position against the offset window, whether or not
 * fragments exist - when there are none, the anchor's own position IS the thing
 * being validated. This fix alone eliminated both false positives on the test MP3
 * files, and improved the EOF-direction code's already-correct symmetric behavior.
 *
 * This does not mean false positives are now impossible - the fragment-chain
 * verification is still greedy/single-candidate (see below), which is a real,
 * remaining simplification relative to DROID's actual multi-candidate exploration.
 * But the specific cases found so far were traceable to this one concrete bug, not
 * to some irreducible gap versus DROID's real rigor.
 */
public class DroidSignatureVerifier {

    // ------------------------------------------------------------------
    // Pattern model: a byte pattern is a list of PatternElements, each of
    // which is either a literal byte, an inclusive byte range, a negated
    // single byte, or a negated multi-byte sequence.
    // ------------------------------------------------------------------

    enum Kind { LITERAL, RANGE, NOT, NOT_SEQUENCE, BITMASK }

    static final class PatternElement {
        final Kind kind;
        final int a; // literal value, single-byte NOT value, range low, or bitmask value
        final int b; // range high (only used for RANGE)
        final byte[] notSequence; // only used for NOT_SEQUENCE (multi-byte negation)
        final boolean inverted; // only used for BITMASK - see bitmask()'s javadoc

        private PatternElement(Kind kind, int a, int b, byte[] notSequence, boolean inverted) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.notSequence = notSequence;
            this.inverted = inverted;
        }

        static PatternElement literal(int value) { return new PatternElement(Kind.LITERAL, value, -1, null, false); }
        static PatternElement not(int value) { return new PatternElement(Kind.NOT, value, -1, null, false); }
        static PatternElement range(int low, int high) { return new PatternElement(Kind.RANGE, low, high, null, false); }
        static PatternElement notSequence(byte[] forbidden) { return new PatternElement(Kind.NOT_SEQUENCE, -1, -1, forbidden, false); }

        /**
         * Bitmask matching - "[&XX]" / "[!&XX]" syntax, confirmed against DROID's
         * own real syntax documentation (github.com/digital-preservation/droid,
         * "Signature syntax.md"), found via two real signatures (1487 and 3426 in
         * the V124 signature file) that this class was previously unable to parse
         * at all - the parser had no concept of "&" inside brackets and threw a
         * NumberFormatException trying to read it as a hex digit.
         *
         * Per the docs: "[&FF] ... will match all bytes which have the same bits
         * set to one in the bitmask" - i.e. (byte & mask) == mask, ALL 1-bits in
         * the mask must also be set in the byte (not merely "any" bit - confirmed
         * by the doc's own multi-bit example, "[&88]" matching bytes with BOTH
         * bit 7 and bit 3 set, an AND-equality check, not an OR/any-bit check).
         * "[!&FF]" is the inverse: (byte & mask) != mask.
         *
         * This is a genuinely different construct from NOT_SEQUENCE (which
         * negates an exact multi-byte literal, e.g. the real "[!4001C8...]" case
         * found earlier) - bitmask always operates on exactly one byte, testing
         * specific bit positions, not a run of literal bytes.
         */
        static PatternElement bitmask(int maskValue, boolean inverted) {
            return new PatternElement(Kind.BITMASK, maskValue, -1, null, inverted);
        }

        int length() {
            return (kind == Kind.NOT_SEQUENCE) ? notSequence.length : 1;
        }

        /** Pure value check - "would this element match this specific byte
         *  value", with no position/array involved. Used only to build BMH shift
         *  tables (see computeForwardShifts()) - never called for NOT_SEQUENCE,
         *  which spans multiple bytes and isn't part of the single-byte shift
         *  model (patterns containing one are simply not BMH-eligible - see
         *  computeForwardShifts()'s javadoc). */
        boolean matchesByteValue(int unsignedByteValue) {
            switch (kind) {
                case LITERAL: return unsignedByteValue == a;
                case NOT: return unsignedByteValue != a;
                case RANGE: return unsignedByteValue >= a && unsignedByteValue <= b;
                case BITMASK: {
                    boolean allMaskBitsSet = (unsignedByteValue & a) == a;
                    return inverted != allMaskBitsSet;
                }
                default: throw new UnsupportedOperationException("NOT_SEQUENCE has no single-byte value check");
            }
        }

        boolean matchesAt(byte[] data, int pos) {
            switch (kind) {
                case LITERAL: return (data[pos] & 0xFF) == a;
                case NOT: return (data[pos] & 0xFF) != a;
                case RANGE: {
                    int v = data[pos] & 0xFF;
                    return v >= a && v <= b;
                }
                case BITMASK: {
                    int v = data[pos] & 0xFF;
                    boolean allMaskBitsSet = (v & a) == a;
                    return inverted != allMaskBitsSet; // != acts as XOR against the boolean invert flag
                }
                case NOT_SEQUENCE: {
                    for (int i = 0; i < notSequence.length; i++) {
                        if ((data[pos + i] & 0xFF) != (notSequence[i] & 0xFF)) return true;
                    }
                    return false;
                }
                default: throw new AssertionError();
            }
        }

        /** Same semantics as matchesAt(byte[], int) above, but reading through a
         *  FileRegion at a long absolute position - see FileRegion's javadoc for why
         *  this exists (large-file support without loading the whole file). An
         *  unavailable byte (FileRegion.byteAt returns -1, meaning the position falls
         *  in the unread "gap" of a bounded head+tail region) is treated as "does not
         *  match" for every Kind, INCLUDING NOT/NOT_SEQUENCE - unavailable data must
         *  never be treated as "the negative condition is satisfied", or a NOT
         *  pattern could wrongly succeed just because we don't have the bytes loaded
         *  to check it against. */
        boolean matchesAt(FileRegion region, long pos) {
            if (kind == Kind.NOT_SEQUENCE) {
                for (int i = 0; i < notSequence.length; i++) {
                    int v = region.byteAt(pos + i);
                    if (v < 0) return false; // unavailable - can't confirm, so don't match
                    if (v != (notSequence[i] & 0xFF)) return true; // any byte differs -> NOT satisfied
                }
                return false; // exact match to the forbidden sequence -> NOT is false
            }
            int v = region.byteAt(pos);
            if (v < 0) return false; // unavailable - can't confirm, so don't match
            switch (kind) {
                case LITERAL: return v == a;
                case NOT: return v != a;
                case RANGE: return v >= a && v <= b;
                case BITMASK: {
                    boolean allMaskBitsSet = (v & a) == a;
                    return inverted != allMaskBitsSet;
                }
                default: throw new AssertionError();
            }
        }
    }

    /**
     * FileRegion - a bounded view of a (potentially huge) file's content, holding
     * only a head window and a tail window in memory instead of the whole file.
     *
     * WHY THIS EXISTS
     * -----------------
     * A naive "read the whole file into one byte[]" approach (what this class did
     * before) cannot work at all above ~2GB (Java arrays are int-indexed, capped at
     * Integer.MAX_VALUE), and is wasteful well below that too - a 4GB WAV file
     * triggered exactly this: java.lang.OutOfMemoryError: Required array size too
     * large, from Files.readAllBytes.
     *
     * DROID's own signature designs make this unnecessary in the vast majority of
     * cases: MAX_ANCHOR_SEARCH_DISTANCE already bounds the FIXED SubSequence anchor
     * search to a small window near BOF or EOF, and most CHAINED SubSequences have
     * an explicit, small SubSeqMaxOffset too. So instead of the whole file, this
     * class now keeps only:
     *   - a "head" window: the first N bytes of the file
     *   - a "tail" window: the last N bytes of the file
     * and any position in between (the "gap") is simply unavailable - byteAt()
     * returns -1 for it, and matching logic (see PatternElement.matchesAt above)
     * treats that as "does not match" rather than crashing.
     *
     * KNOWN LIMITATION: a small number of real signatures use a CHAINED (non-first)
     * SubSequence with NO explicit SubSeqMaxOffset (e.g. MP4 signature 278's "moov"
     * box search after "ftyp" - see matchChainedSubSequence), meaning DROID's real
     * semantics is "search forward with no fixed limit". For a file small enough to
     * fit in memory this was already handled by simply searching to data.length.
     * For a file too large to load fully, such an unbounded chained search can only
     * search as far as the loaded head/tail windows actually reach - if the real
     * match is further into the unloaded gap (e.g. "moov" sitting in the middle of
     * a multi-GB MP4), it will not be found. This mirrors the same kind of
     * pragmatic tradeoff real DROID makes with its own maxBytesToScan safety cap
     * (see the class javadoc's "NOT IMPLEMENTED" section) - correctness for
     * reference-anchored (BOF/EOF) matching is preserved regardless of file size;
     * only unbounded middle-of-file searches on huge files are affected, and only
     * for the minority of signatures structured that way.
     */
    static final class FileRegion {
        final byte[] head;
        final byte[] tail; // may be null if the whole file fit in `head`
        final long length; // true total content length
        final long tailStartAbsolute; // absolute position where `tail` begins, or `length` if tail is null

        FileRegion(byte[] head, byte[] tail, long length) {
            this.head = head;
            this.tail = tail;
            this.length = length;
            this.tailStartAbsolute = (tail == null) ? length : (length - tail.length);
        }

        /** Returns the unsigned byte value at absolute position `pos`, or -1 if
         *  `pos` is out of range or falls in the unloaded gap between head and tail. */
        int byteAt(long pos) {
            if (pos < 0 || pos >= length) return -1;
            if (pos < head.length) return head[(int) pos] & 0xFF;
            if (tail != null && pos >= tailStartAbsolute) return tail[(int) (pos - tailStartAbsolute)] & 0xFF;
            return -1; // in the gap - not loaded
        }
    }

    static int patternLength(List<PatternElement> pattern) {
        int total = 0;
        for (PatternElement e : pattern) total += e.length();
        return total;
    }

    /**
     * Precomputes a Boyer-Moore-Horspool forward shift table for a pattern, or
     * returns null if the pattern isn't BMH-eligible.
     *
     * ADAPTED DIRECTLY FROM REAL DROID'S OWN SEARCH ENGINE: this is a port of
     * net.byteseek.searcher.sequence.horspool.BoyerMooreHorspoolSearcher's
     * ForwardInfoFactory.create() (github.com/nishihatapalmer/byteseek, BSD
     * 3-clause licensed - same license family as this project), the actual
     * search algorithm DROID itself uses (via its byteseek dependency). DROID's
     * own signature XML already carries the DefaultShift/Shift skip-table
     * values this algorithm needs (parsed by this class from day one, but never
     * previously used for anything - every anchor/fragment search was a naive
     * byte-by-byte position scan instead). This was found to be a major
     * remaining source of DROID-light being slower than real DROID even after
     * fixing the earlier per-call allocation overhead - a real production
     * comparison showed ~10x slower on an ordinary small JPEG (32ms vs 3ms),
     * with anchor-match counting confirming the raw scan-position volume was
     * already roughly equal between content types, pointing squarely at
     * algorithmic search efficiency as the remaining gap.
     *
     * HOW BMH WORKS (brief): rather than testing every candidate start position
     * one at a time, it checks the byte aligned with the LAST position of the
     * pattern first. If that byte couldn't possibly match anything at the last
     * position, the precomputed shift table tells us how far we can safely
     * skip ahead - up to the whole pattern length - without missing a possible
     * match, based on where else (if anywhere) that byte value could appear in
     * the pattern. Longer patterns tend to allow bigger skips, counterintuitively
     * making BMH relatively BETTER on longer anchors, not worse.
     *
     * NOT BMH-ELIGIBLE: a pattern containing a NOT_SEQUENCE element (a
     * multi-byte negated literal - see PatternElement.notSequence()'s javadoc)
     * doesn't fit the single-byte-per-position shift model at all, so this
     * returns null for those, and callers fall back to the naive scan for that
     * specific pattern only - correctness is unaffected either way, only speed.
     */
    static int[] computeForwardShifts(List<PatternElement> pattern) {
        for (PatternElement e : pattern) {
            if (e.kind == Kind.NOT_SEQUENCE) return null; // not BMH-eligible - see javadoc above
        }
        int len = pattern.size(); // one element = one byte position, guaranteed (no NOT_SEQUENCE)
        if (len == 0) return null;
        int lastPos = len - 1;

        // Pathological-case guard, exactly mirroring byteseek's own handling: if
        // some position matches EVERY possible byte value (e.g. a RANGE covering
        // the whole 00-FF range, or a BITMASK with mask 0), no shift can safely
        // be bigger than the distance from that position to the end - otherwise
        // a wildcard-like position would let unsafe, overly large skips through.
        int maxShift = len;
        for (int pos = lastPos; pos >= 0; pos--) {
            if (matchesAllByteValues(pattern.get(pos))) {
                maxShift = len - pos;
                break;
            }
        }

        int[] shifts = new int[256];
        Arrays.fill(shifts, maxShift);

        if (maxShift > 1) {
            int processShiftsFromPos = len - maxShift;
            // Shift for a given byte value = its distance from the end of the
            // pattern, for every position that byte could appear at (except the
            // very last position, which doesn't need a shift entry of its own).
            for (int pos = processShiftsFromPos; pos < lastPos; pos++) {
                PatternElement e = pattern.get(pos);
                int distanceFromEnd = len - pos - 1;
                for (int b = 0; b < 256; b++) {
                    if (e.matchesByteValue(b)) {
                        shifts[b] = distanceFromEnd;
                    }
                }
            }
        }
        return shifts;
    }

    /** Whether a single PatternElement matches every possible byte value 0-255 -
     *  the "wildcard position" case computeForwardShifts() needs to guard
     *  against (see its javadoc). Checked by brute force (256 checks, only ever
     *  run once per pattern at parse time, not per scan) rather than reasoning
     *  about each Kind specially, to avoid a subtle correctness bug in that
     *  reasoning for one kind quietly breaking the safety guard. */
    static boolean matchesAllByteValues(PatternElement e) {
        for (int b = 0; b < 256; b++) {
            if (!e.matchesByteValue(b)) return false;
        }
        return true;
    }

    /**
     * Backward-direction counterpart to computeForwardShifts() - adapted from
     * byteseek's BoyerMooreHorspoolSearcher.BackwardInfoFactory.create() (see
     * computeForwardShifts()'s javadoc for full provenance). Used by
     * matchFixedSubSequence()'s EOF-anchored (searchBackward) branch, which
     * searches candidate anchor positions moving away from a reference point -
     * exactly the same kind of loop the fragment/chained-subsequence searches
     * already had BMH-accelerated, but this one operates on the FIRST byte of
     * the anchor (searching backward) rather than the last (searching forward).
     *
     * Found to matter in practice: a real production comparison showed small
     * files still running 10-20x slower than real DROID even after the forward
     * BMH fix, because matchFixedSubSequence's own candidate loop - which this
     * accelerates - was still using a naive one-position-at-a-time scan.
     */
    static int[] computeBackwardShifts(List<PatternElement> pattern) {
        for (PatternElement e : pattern) {
            if (e.kind == Kind.NOT_SEQUENCE) return null; // not BMH-eligible - see computeForwardShifts()
        }
        int len = pattern.size();
        if (len == 0) return null;

        // Pathological-case guard, mirrored from the forward case but checked
        // from the START of the pattern instead of the end.
        int maxShift = len;
        for (int pos = 0; pos < len; pos++) {
            if (matchesAllByteValues(pattern.get(pos))) {
                maxShift = pos + 1;
                break;
            }
        }

        int[] shifts = new int[256];
        Arrays.fill(shifts, maxShift);

        if (maxShift > 1) {
            int processShiftsFromPos = maxShift - 1;
            // Shift for a byte value = its position in the pattern, for every
            // position that byte could appear at (except position 0, which
            // doesn't need a shift entry of its own - matching byteseek exactly).
            for (int pos = processShiftsFromPos; pos > 0; pos--) {
                PatternElement e = pattern.get(pos);
                for (int b = 0; b < 256; b++) {
                    if (e.matchesByteValue(b)) {
                        shifts[b] = pos;
                    }
                }
            }
        }
        return shifts;
    }

    /**
     * Boyer-Moore-Horspool forward search within a single contiguous byte[]
     * array - e.g. a FileRegion's head or tail buffer on its own, never
     * crossing between them (the caller, findFirstForward(), already handles
     * splitting a search across the head/tail boundary - see its javadoc for
     * why that split exists and must stay outside this method).
     *
     * ADAPTED DIRECTLY FROM byteseek's BoyerMooreHorspoolSearcher.searchForwards
     * (byte[], int, int) - see computeForwardShifts()'s javadoc for provenance.
     *
     * @param fromLocal            first valid START position to consider, local
     *                             to `array`
     * @param toLocalInclusive     last valid START position to consider, local
     *                             to `array`
     * @return the local array index where pattern starts, or -1 if not found
     */
    static int bmhSearchForwardInArray(byte[] array, int fromLocal, int toLocalInclusive,
                                        List<PatternElement> pattern, int[] shifts) {
        int lastPos = pattern.size() - 1;
        PatternElement lastElement = pattern.get(lastPos);

        int searchPosition = fromLocal + lastPos;
        int finalPosition = toLocalInclusive + lastPos;
        int lastArrayIndex = array.length - 1;
        if (finalPosition > lastArrayIndex) finalPosition = lastArrayIndex;

        while (searchPosition <= finalPosition) {
            int currentByte = array[searchPosition] & 0xFF;
            while (!lastElement.matchesByteValue(currentByte)) {
                searchPosition += shifts[currentByte];
                if (searchPosition > finalPosition) return -1;
                currentByte = array[searchPosition] & 0xFF;
            }
            int startMatchPosition = searchPosition - lastPos;
            if (matchesAt(array, startMatchPosition, pattern)) {
                return startMatchPosition;
            }
            searchPosition += shifts[currentByte];
        }
        return -1;
    }

    /** Same semantics as matchesAt(FileRegion, long, List) but operating on a
     *  plain byte[] (used by bmhSearchForwardInArray, which already works
     *  within a single fully-available contiguous array, so there's no
     *  "unavailable position" case to handle here). Revives what was previously
     *  dead code (this overload existed from before the FileRegion large-file
     *  fix but had no remaining callers) for genuine use in the BMH path. */
    static boolean matchesAt(byte[] data, int start, List<PatternElement> pattern) {
        if (start < 0) return false;
        int pos = start;
        for (PatternElement e : pattern) {
            if (pos + e.length() > data.length) return false;
            if (!e.matchesAt(data, pos)) return false;
            pos += e.length();
        }
        return true;
    }

    static List<PatternElement> parsePattern(String text) {
        List<PatternElement> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[') {
                int close = text.indexOf(']', i);
                String inner = text.substring(i + 1, close);
                if (inner.startsWith("!&")) {
                    // Inverted bitmask, e.g. "[!&01]" - real DROID syntax, see
                    // PatternElement.bitmask()'s javadoc. Must be checked BEFORE
                    // the plain "!" branch below, since this also starts with "!".
                    out.add(PatternElement.bitmask(hexByte(inner.substring(2)), true));
                } else if (inner.startsWith("&")) {
                    // Bitmask, e.g. "[&88]".
                    out.add(PatternElement.bitmask(hexByte(inner.substring(1)), false));
                } else if (inner.startsWith("!")) {
                    String hex = inner.substring(1);
                    if (hex.length() == 2) {
                        out.add(PatternElement.not(hexByte(hex)));
                    } else {
                        out.add(PatternElement.notSequence(hexBytes(hex)));
                    }
                } else {
                    String[] parts = inner.split(":");
                    out.add(PatternElement.range(hexByte(parts[0]), hexByte(parts[1])));
                }
                i = close + 1;
            } else {
                out.add(PatternElement.literal(hexByte(text.substring(i, i + 2))));
                i += 2;
            }
        }
        return out;
    }

    static int hexByte(String twoHexChars) {
        return Integer.parseInt(twoHexChars, 16);
    }

    static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * Endianness="Little-endian" handling: a NO-OP.
     *
     * An earlier version of this class reversed anchor/fragment byte order for any
     * "Little-endian" ByteSequence, on the assumption that multi-byte values needed
     * flipping to match real file bytes. Direct testing against a real MP3 file
     * disproved this: signature 266's EOF-anchored SubSequence (Little-endian,
     * anchor "FFFB") is meant to match the literal, UNREVERSED bytes 0xFF 0xFB -
     * exactly what's present in a real file's trailing audio frames. Reversing it
     * broke a genuine, real-DROID-confirmed match (PUID fmt/134) entirely.
     *
     * Reversal is therefore a no-op here. This does not mean Endianness carries no
     * meaning in DROID's real semantics - only that "reverse the whole anchor byte
     * sequence" is empirically wrong, and applying that guess was actively harmful.
     * Treat this gap as UNRESOLVED, not fixed.
     */
    static List<PatternElement> reversed(List<PatternElement> pattern) {
        return pattern;
    }

    // ------------------------------------------------------------------
    // Signature structure model
    // ------------------------------------------------------------------

    static final class Fragment {
        final int position;
        final int minOffset;
        final int maxOffset;
        final List<PatternElement> pattern;

        // PERFORMANCE: precomputed once by precomputeOrdering() - see
        // computeForwardShifts()'s javadoc for what this is and why it matters
        // (real Boyer-Moore-Horspool searching instead of naive byte-by-byte
        // position scanning). Null if this pattern isn't BMH-eligible (contains
        // a NOT_SEQUENCE element), in which case findFirstForward() falls back
        // to the naive scan for this pattern only.
        int[] forwardShifts;

        Fragment(int position, int minOffset, int maxOffset, List<PatternElement> pattern) {
            this.position = position;
            this.minOffset = minOffset;
            this.maxOffset = maxOffset;
            this.pattern = pattern;
        }
    }

    static final class SubSequenceDef {
        int position;
        List<PatternElement> anchor;
        int minSeqOffset;
        int maxSeqOffset; // -1 means "attribute absent" = unbounded
        final List<Fragment> leftFragments = new ArrayList<>();
        final List<Fragment> rightFragments = new ArrayList<>();

        // PERFORMANCE: precomputed once (see precomputeOrdering(), called at the
        // end of parseSignatures()) instead of being recomputed by groupByPosition()
        // on every single verifyRightFragmentChain/verifyLeftFragmentChain call.
        // Fragments never change after parsing, so grouping them by Position is
        // pure repeated waste otherwise - and these methods are called once per
        // anchor candidate TRIED, not once per signature, so this was a real,
        // measurable cost multiplied across every scan. See class javadoc
        // "PERFORMANCE" note for the concrete numbers that motivated this.
        List<List<Fragment>> groupedRightFragments;
        List<List<Fragment>> groupedLeftFragments;

        // PERFORMANCE: precomputed BMH shift table for the anchor itself - see
        // Fragment.forwardShifts's comment and computeForwardShifts()'s javadoc.
        int[] anchorForwardShifts;
        // Backward-direction counterpart, used by matchFixedSubSequence()'s
        // EOF-anchored branch - see computeBackwardShifts()'s javadoc.
        int[] anchorBackwardShifts;
    }

    enum Reference { BOF, EOF, ANYWHERE }

    static final class ByteSequenceDef {
        Reference reference = Reference.BOF;
        boolean littleEndian = false;
        final List<SubSequenceDef> subSequences = new ArrayList<>();

        // PERFORMANCE: precomputed once (see precomputeOrdering()) instead of
        // being re-sorted (and possibly re-reversed) by matchByteSequence() on
        // every single call - subSequences never changes after parsing, so this
        // was pure repeated allocation/sorting overhead, once per signature per
        // file scanned. See class javadoc "PERFORMANCE" note.
        List<SubSequenceDef> orderedSubSequences;
    }

    static final class InternalSignatureDef {
        final int id;
        final List<ByteSequenceDef> byteSequences = new ArrayList<>();
        InternalSignatureDef(int id) { this.id = id; }
    }

    // ------------------------------------------------------------------
    // Low-level pattern search helpers
    // ------------------------------------------------------------------

    static boolean matchesAt(FileRegion region, long start, List<PatternElement> pattern) {
        if (start < 0) return false;
        long pos = start;
        for (PatternElement e : pattern) {
            if (pos + e.length() > region.length) return false;
            if (!e.matchesAt(region, pos)) return false;
            pos += e.length();
        }
        return true;
    }

    /** First position in [fromInclusive, toInclusive] where pattern matches, or -1.
     *  Searches in increasing offset order (i.e. "leftmost/earliest first"). Uses
     *  long positions throughout - a file's length can exceed Integer.MAX_VALUE
     *  (this is exactly what caused the original OutOfMemoryError on a 4GB file).
     *
     *  IMPORTANT for FileRegion-backed large files: this searches ONLY the
     *  portions of [fromInclusive, toInclusive] that actually fall within the
     *  head buffer and/or the tail buffer, in that order (preserving "leftmost
     *  match first" semantics) - never wasting time iterating through the
     *  unloaded "gap" between them (see the MAX_ANCHOR_SEARCH_DISTANCE-adjacent
     *  performance note this class carries elsewhere for why that matters on a
     *  multi-GB file with a common/short anchor).
     *
     *  BUG FIX: an earlier version of this method clamped the search to stop
     *  at the end of whichever buffer `fromInclusive` fell within, and never
     *  even attempted the other buffer - even when the two are directly
     *  contiguous (no real gap at all) or when `toInclusive` genuinely extends
     *  into the tail. This broke real, ordinary files: a real 39MB MP4 (not
     *  "fast-start" optimized) had its "moov" box at offset ~39.07M, about 19MB
     *  past the head/tail boundary when read via detect(InputStream) - fully
     *  available in the tail buffer, but never reached, causing a real,
     *  everyday MP4 to go completely undetected via the InputStream entry
     *  point (while detect(File) worked fine, since a 39MB file is well under
     *  LARGE_FILE_THRESHOLD_BYTES and gets read as one single, ungapped
     *  buffer there). Fixed by searching the head sub-range first, then
     *  continuing into the tail sub-range (only the portion of it that falls
     *  within [fromInclusive, toInclusive]) if nothing was found in head.
     *
     *  PERFORMANCE: uses real Boyer-Moore-Horspool search (see
     *  computeForwardShifts()'s javadoc) via the precomputed `shifts` table
     *  when one is available, instead of a naive byte-by-byte position scan -
     *  applied separately within the head sub-range and the tail sub-range
     *  (never crossing the boundary in one BMH call, since bmhSearchForwardInArray
     *  operates on a single plain array). Falls back to the naive scan when
     *  `shifts` is null (pattern contains a NOT_SEQUENCE element - see
     *  computeForwardShifts()'s javadoc for why that's not BMH-eligible). */
    static long findFirstForward(FileRegion region, long fromInclusive, long toInclusive,
                                  List<PatternElement> pattern, int[] shifts) {
        long patLen = patternLength(pattern);
        long lo = Math.max(0, fromInclusive);
        long hi = Math.min(toInclusive, region.length - patLen);
        if (hi < lo) return -1;

        // Search the head-buffer portion of the requested range first (lowest
        // positions - preserves "leftmost match first").
        long headHi = Math.min(hi, region.head.length - 1);
        if (lo <= headHi) {
            if (shifts != null) {
                int found = bmhSearchForwardInArray(region.head, (int) lo, (int) headHi, pattern, shifts);
                if (found >= 0) return found;
            } else {
                for (long start = lo; start <= headHi; start++) {
                    if (matchesAt(region, start, pattern)) return start;
                }
            }
        }

        // Continue into the tail buffer's portion of the requested range, if
        // any - correctly handles both "head and tail are directly contiguous,
        // no real gap" (the MP4 case above) and "there's a genuine unloaded gap
        // in between" (the gap itself is simply never iterated, matching
        // FileRegion.byteAt's -1-for-unavailable semantics, without wasting
        // time scanning through it).
        if (region.tail != null) {
            long tailLo = Math.max(lo, region.tailStartAbsolute);
            if (tailLo <= hi) {
                if (shifts != null) {
                    int localLo = (int) (tailLo - region.tailStartAbsolute);
                    int localHi = (int) (hi - region.tailStartAbsolute);
                    int found = bmhSearchForwardInArray(region.tail, localLo, localHi, pattern, shifts);
                    if (found >= 0) return found + region.tailStartAbsolute;
                } else {
                    for (long start = tailLo; start <= hi; start++) {
                        if (matchesAt(region, start, pattern)) return start;
                    }
                }
            }
        }

        return -1;
    }

    // ------------------------------------------------------------------
    // Fragment chain verification (greedy / first-match-wins across POSITION STEPS -
    // see class javadoc for why this is a deliberate simplification of DROID's real,
    // more thorough, but combinatorially-dangerous multi-alternative exploration).
    //
    // BUG FIX (found via signature 128 / MS Word, confirmed again on a real MP4 file
    // and signature 278): multiple Fragment elements can share the SAME Position
    // number, meaning "match ANY ONE of these alternatives here" (OR), not "match
    // all of these one after another" (AND/sequential). An earlier version of this
    // class sorted all fragments by position and walked them as one flat sequential
    // chain, so same-Position fragments were wrongly treated as separate mandatory
    // steps - e.g. signature 278 (MP4) requires ONE of "iso2"/"isom"/"mp41"/"mp42"
    // (all Position=1) after "ftyp", but the old code tried "iso2" first, found it
    // absent, and failed the WHOLE chain without ever trying "isom" or "mp42" - even
    // though the real file plainly contained "mp42" right where expected. Fixed by
    // grouping fragments by Position first, then treating each group as a set of
    // alternatives: the step succeeds if ANY pattern in the group matches within the
    // shared offset window, and the cursor advances by whichever one actually
    // matched (earliest match position wins if more than one alternative fits).
    // ------------------------------------------------------------------

    /** Groups fragments by Position (ascending), preserving each group's members in
     *  their original (file) order - used so same-Position fragments can be tried
     *  as alternatives rather than sequential steps. */
    static List<List<Fragment>> groupByPosition(List<Fragment> fragments) {
        TreeMap<Integer, List<Fragment>> grouped = new TreeMap<>();
        for (Fragment f : fragments) {
            grouped.computeIfAbsent(f.position, k -> new ArrayList<>()).add(f);
        }
        return new ArrayList<>(grouped.values());
    }

    /** Verifies the RightFragment chain starting right after an anchor match.
     *  Returns the cursor position immediately after the whole chain (i.e. one past
     *  the last matched fragment byte), or -1 if any step in the chain fails. Each
     *  step is a group of same-Position fragments; the step succeeds if ANY ONE of
     *  them matches (see BUG FIX note above).
     *
     *  Takes the ALREADY-GROUPED fragment list (SubSequenceDef.groupedRightFragments,
     *  precomputed once by precomputeOrdering()) rather than grouping fresh here -
     *  see the PERFORMANCE note on precomputeOrdering() for why. */
    static long verifyRightFragmentChain(FileRegion region, long afterAnchor, List<List<Fragment>> groupedRightFragments) {
        long cursor = afterAnchor;
        for (List<Fragment> alternatives : groupedRightFragments) {
            long bestFound = -1;
            int bestPatternLen = -1;
            for (Fragment f : alternatives) {
                long from = cursor + f.minOffset;
                long to = cursor + f.maxOffset;
                long found = findFirstForward(region, from, to, f.pattern, f.forwardShifts);
                if (found >= 0 && (bestFound < 0 || found < bestFound)) {
                    bestFound = found;
                    bestPatternLen = patternLength(f.pattern);
                }
            }
            if (bestFound < 0) return -1;
            cursor = bestFound + bestPatternLen;
        }
        return cursor;
    }

    /** Verifies the LeftFragment chain ending right before an anchor match.
     *  Returns the cursor position at the start of the whole chain (i.e. the
     *  earliest matched fragment byte), or -1 if any step in the chain fails. Same
     *  same-Position-is-OR handling as verifyRightFragmentChain (see above).
     *  Takes the ALREADY-GROUPED fragment list, same as verifyRightFragmentChain -
     *  see its javadoc. */
    static long verifyLeftFragmentChain(FileRegion region, long beforeAnchor, List<List<Fragment>> groupedLeftFragments) {
        long cursor = beforeAnchor;
        for (List<Fragment> alternatives : groupedLeftFragments) {
            long bestFound = -1;
            for (Fragment f : alternatives) {
                int patLen = patternLength(f.pattern);
                long to = cursor - f.minOffset - patLen;
                long from = cursor - f.maxOffset - patLen;
                long found = findFirstForward(region, from, to, f.pattern, f.forwardShifts);
                // "Earliest" for a left fragment means closest to the anchor, i.e.
                // the LARGEST found offset (rightmost within its window).
                if (found >= 0 && found > bestFound) {
                    bestFound = found;
                }
            }
            if (bestFound < 0) return -1;
            cursor = bestFound;
        }
        return cursor;
    }

    // ------------------------------------------------------------------
    // SubSequence matching
    // ------------------------------------------------------------------

    /**
     * Matches a CHAINED (non-first) SubSequence: its anchor must simply be found
     * within [cursor+minSeqOffset, cursor+maxSeqOffset] of wherever the previous
     * SubSequence in the chain ended. No reference-point endpoint validity check
     * applies here (matching real DROID: that check only gates the SubSequence
     * that's directly anchored to the ByteSequence's BOF/EOF reference point).
     * Returns the new cursor (end of this SubSequence's full match), or -1.
     */
    static long matchChainedSubSequence(FileRegion region, SubSequenceDef sub, long cursor) {
        if (sub.anchor == null) return -1;
        long from = cursor + sub.minSeqOffset;
        long to = (sub.maxSeqOffset >= 0) ? (cursor + sub.maxSeqOffset) : region.length;
        long anchorPos = findFirstForward(region, from, to, sub.anchor, sub.anchorForwardShifts);
        if (anchorPos < 0) return -1;
        long afterAnchor = anchorPos + patternLength(sub.anchor);
        long afterRight = verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
        if (afterRight < 0) return -1;
        long beforeLeft = verifyLeftFragmentChain(region, anchorPos, sub.groupedLeftFragments);
        if (beforeLeft < 0) return -1;
        return afterRight;
    }

    /**
     * Safety cap on how far back/forward the FIXED SubSequence anchor search will
     * look, in bytes, from the reference point. Without this, a non-matching
     * signature causes a naive byte-by-byte scan of the ENTIRE remaining file (since
     * the corrected offset semantics - see class javadoc - no longer bound the
     * anchor's own search window). Real DROID has an analogous "maxBytesToScan"
     * safety cap for the same reason. This value is a pragmatic, undocumented choice
     * (not taken from DROID's real default) - large enough to comfortably cover the
     * ID3v2-tag case that motivated this rewrite (real tags seen so far: ~2.5KB and
     * ~259KB), small enough to keep a full 2,018-signature scan of a large file
     * from effectively hanging on every non-matching signature.
     */
    static int MAX_ANCHOR_SEARCH_DISTANCE = 3000;

    /**
     * Gates the per-file diagnostic console output in detect(File)/
     * detect(InputStream)/detectFromRegion() (the "Verifying against...",
     * "Verification of all X signatures completed...", "Raw matched signature
     * IDs...", etc. lines) - NOT the one-time constructor parse summary, and
     * NOT matchByteSequenceDebug()'s manual single-signature tracing output,
     * both of which remain unconditional.
     *
     * Defaults to true (existing, unchanged behavior) so nothing silently
     * changes for existing callers - set to false before a large-scale batch
     * run to remove this as a cost entirely. Found to matter in practice: even
     * a best-case measurement (single-threaded, output redirected to
     * /dev/null) showed ~0.04ms per print call, ~0.2ms total per detect() call
     * for the 5 lines this prints - and a real multi-threaded production
     * environment, where System.out (a synchronized PrintStream) can see real
     * lock contention across threads, is very likely worse. For a small file
     * where actual verification is only a few ms, that's a proportionally
     * significant, entirely avoidable cost during a bulk run.
     */
    static boolean VERBOSE = false;

    /**
     * Matches the FIXED SubSequence of a ByteSequence - the one directly anchored to
     * the reference point (BOF or EOF). This is where the corrected offset semantics
     * apply: minSeqOffset/maxSeqOffset bound where the fragment chain's END lands
     * relative to referencePosition, NOT where the anchor itself is found. The anchor
     * search tries candidates working outward from the reference point until one
     * produces a validly-placed chain (see class javadoc for the real-source basis
     * of this correction) - bounded by MAX_ANCHOR_SEARCH_DISTANCE (see its javadoc).
     *
     * @param referencePosition for EOF: typically data.length (one past the last
     *                          byte); for BOF: typically 0.
     * @param searchBackward    true for EOF (search anchor candidates going backward
     *                          from referencePosition), false for BOF (forward).
     * @return the new cursor (end of this SubSequence's full match, for chaining to
     *         the next SubSequence, if any), or -1 if no valid candidate exists.
     */
    static long matchFixedSubSequence(FileRegion region, SubSequenceDef sub, long referencePosition, boolean searchBackward) {
        if (sub.anchor == null) return -1;
        int anchorLen = patternLength(sub.anchor);
        long maxOffset = (sub.maxSeqOffset >= 0) ? sub.maxSeqOffset : Long.MAX_VALUE / 2;
        int minOffset = sub.minSeqOffset;

        if (searchBackward) {
            // EOF-anchored: try anchor candidates starting close to the reference
            // point and moving further back (away from EOF) until the RIGHT fragment
            // chain's end satisfies [referencePosition-maxOffset, referencePosition-minOffset].
            long candidate = referencePosition - anchorLen; // closest possible to reference point
            long floor = Math.max(0, referencePosition - MAX_ANCHOR_SEARCH_DISTANCE);
            int[] shifts = sub.anchorBackwardShifts;

            // PERFORMANCE: BMH-accelerated candidate search (see
            // computeBackwardShifts()'s javadoc for why this matters - this loop
            // was found to still dominate cost on small files even after the
            // forward-direction fragment/chained-subsequence BMH fix, since every
            // signature's FIXED anchor search used to check every single
            // candidate position one at a time regardless of content). Checks
            // the anchor's FIRST byte (searching backward); when it can't
            // possibly match, skips back by the precomputed safe distance
            // instead of just one position. When a full anchor+chain match
            // attempt FAILS verification, this also shifts by the same safe
            // distance rather than falling back to a single step - exactly
            // mirroring byteseek's own real search algorithm (see
            // computeForwardShifts()'s javadoc), which does the same thing.
            if (shifts != null) {
                PatternElement firstElement = sub.anchor.get(0);
                while (candidate >= floor) {
                    int currentByte = region.byteAt(candidate);
                    if (currentByte < 0) { candidate--; continue; } // unavailable - safe minimal step
                    while (!firstElement.matchesByteValue(currentByte)) {
                        candidate -= shifts[currentByte];
                        if (candidate < floor) return -1;
                        int next = region.byteAt(candidate);
                        if (next < 0) { candidate--; currentByte = -1; break; } // unavailable - safe minimal step
                        currentByte = next;
                    }
                    if (currentByte < 0) continue; // hit the unavailable-byte fallback above - re-check loop condition
                    if (matchesAt(region, candidate, sub.anchor)) {
                        long afterAnchor = candidate + anchorLen;
                        long chainEnd = verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
                        if (chainEnd >= 0) {
                            long distanceFromReference = referencePosition - chainEnd;
                            if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                                long beforeLeft = verifyLeftFragmentChain(region, candidate, sub.groupedLeftFragments);
                                if (beforeLeft >= 0) {
                                    return chainEnd;
                                }
                            }
                        }
                    }
                    candidate -= shifts[currentByte];
                }
                return -1;
            }

            // Fallback: naive one-position-at-a-time scan, used when the anchor
            // isn't BMH-eligible (contains a NOT_SEQUENCE element).
            while (candidate >= floor) {
                if (matchesAt(region, candidate, sub.anchor)) {
                    long afterAnchor = candidate + anchorLen;
                    long chainEnd = verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
                    if (chainEnd >= 0) {
                        long distanceFromReference = referencePosition - chainEnd;
                        if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                            long beforeLeft = verifyLeftFragmentChain(region, candidate, sub.groupedLeftFragments);
                            if (beforeLeft >= 0) {
                                return chainEnd;
                            }
                        }
                    }
                }
                candidate--;
            }
            return -1;
        } else {
            // BOF-anchored: try anchor candidates starting close to the reference
            // point and moving forward until the LEFT fragment chain's start
            // satisfies [referencePosition+minOffset, referencePosition+maxOffset].
            // (Symmetric to the EOF case above; see real source's forward branch.)
            long candidate = referencePosition; // closest possible to reference point
            long ceiling = Math.min(region.length - anchorLen, referencePosition + MAX_ANCHOR_SEARCH_DISTANCE);
            int[] shifts = sub.anchorForwardShifts;

            // PERFORMANCE: BMH-accelerated, symmetric to the backward branch above,
            // checking the anchor's LAST byte and shifting forward.
            if (shifts != null) {
                int lastPos = sub.anchor.size() - 1;
                PatternElement lastElement = sub.anchor.get(lastPos);
                while (candidate <= ceiling) {
                    long checkPos = candidate + lastPos;
                    int currentByte = region.byteAt(checkPos);
                    if (currentByte < 0) { candidate++; continue; } // unavailable - safe minimal step
                    while (!lastElement.matchesByteValue(currentByte)) {
                        candidate += shifts[currentByte];
                        if (candidate > ceiling) return -1;
                        checkPos = candidate + lastPos;
                        int next = region.byteAt(checkPos);
                        if (next < 0) { candidate++; currentByte = -1; break; } // unavailable - safe minimal step
                        currentByte = next;
                    }
                    if (currentByte < 0) continue; // hit the unavailable-byte fallback above - re-check loop condition
                    if (matchesAt(region, candidate, sub.anchor)) {
                        long beforeLeft = verifyLeftFragmentChain(region, candidate, sub.groupedLeftFragments);
                        if (beforeLeft >= 0) {
                            // BUG FIX: previously this check was skipped entirely when there were
                            // no left fragments ("sub.leftFragments.isEmpty() || ..."), on the wrong
                            // assumption that "nothing to check" meant "no offset constraint at all".
                            // That let signature 10 (TIFF, anchor "4D4D002A", SubSeqMinOffset=0,
                            // SubSeqMaxOffset=0, NO fragments) match a byte-4 coincidence anywhere
                            // within MAX_ANCHOR_SEARCH_DISTANCE, instead of requiring it AT byte 0
                            // exactly - found via a real MP3 file where those 4 bytes happened to
                            // occur at offset 339, inside an ID3v2 tag, and were wrongly accepted.
                            // When there are no left fragments, verifyLeftFragmentChain returns
                            // `candidate` unchanged, so this check now correctly constrains the
                            // ANCHOR's own position directly - exactly what SubSeqMinOffset/
                            // SubSeqMaxOffset mean for a fragment-less SubSequence.
                            long distanceFromReference = beforeLeft - referencePosition;
                            boolean leftOk = distanceFromReference >= minOffset && distanceFromReference <= maxOffset;
                            if (leftOk) {
                                long afterAnchor = candidate + anchorLen;
                                long chainEnd = verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
                                if (chainEnd >= 0) {
                                    return chainEnd;
                                }
                            }
                        }
                    }
                    candidate += shifts[currentByte];
                }
                return -1;
            }

            // Fallback: naive one-position-at-a-time scan, used when the anchor
            // isn't BMH-eligible (contains a NOT_SEQUENCE element).
            while (candidate <= ceiling) {
                if (matchesAt(region, candidate, sub.anchor)) {
                    long beforeLeft = verifyLeftFragmentChain(region, candidate, sub.groupedLeftFragments);
                    if (beforeLeft >= 0) {
                        long distanceFromReference = beforeLeft - referencePosition;
                        boolean leftOk = distanceFromReference >= minOffset && distanceFromReference <= maxOffset;
                        if (leftOk) {
                            long afterAnchor = candidate + anchorLen;
                            long chainEnd = verifyRightFragmentChain(region, afterAnchor, sub.groupedRightFragments);
                            if (chainEnd >= 0) {
                                return chainEnd;
                            }
                        }
                    }
                }
                candidate++;
            }
            return -1;
        }
    }

    /**
     * Verifies a whole ByteSequence: its SubSequences (there may be several,
     * chained) must ALL match, in Position order for BOF/Anywhere, or REVERSE
     * Position order for EOF (matching real DROID's ByteSequence.matches(), which
     * iterates seq.length-1 down to 0 for EOFoffset ByteSequences).
     */
    static boolean matchByteSequence(FileRegion region, ByteSequenceDef bs) {
        List<SubSequenceDef> subs = bs.orderedSubSequences;
        boolean backward = (bs.reference == Reference.EOF);

        long referencePosition = backward ? region.length : 0;
        boolean first = true;
        long cursor = -1;
        for (SubSequenceDef sub : subs) {
            long result = first
                    ? matchFixedSubSequence(region, sub, referencePosition, backward)
                    : matchChainedSubSequence(region, sub, cursor);
            if (result < 0) return false;
            cursor = result;
            first = false;
        }
        return true;
    }

    /** A signature matches only if ALL of its ByteSequences match. */
    static boolean matchSignature(FileRegion region, InternalSignatureDef sig) {
        for (ByteSequenceDef bs : sig.byteSequences) {
            if (!matchByteSequence(region, bs)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Debug tracing - traces one signature's matching attempt step by step.
    // ------------------------------------------------------------------

    static boolean matchByteSequenceDebug(FileRegion region, ByteSequenceDef bs) {
        List<SubSequenceDef> subs = new ArrayList<>(bs.subSequences);
        subs.sort(Comparator.comparingInt(s -> s.position));
        boolean backward = (bs.reference == Reference.EOF);
        if (backward) Collections.reverse(subs);

        long referencePosition = backward ? region.length : 0;
        System.out.printf("    reference=%s referencePosition=%,d%n", bs.reference, referencePosition);

        boolean first = true;
        long cursor = -1;
        for (SubSequenceDef sub : subs) {
            System.out.printf("    SubSequence Position %d (%s): minSeqOffset=%d maxSeqOffset=%s%n",
                    sub.position, first ? "FIXED to reference point" : "chained",
                    sub.minSeqOffset, sub.maxSeqOffset >= 0 ? String.valueOf(sub.maxSeqOffset) : "unbounded");

            long result = first
                    ? matchFixedSubSequence(region, sub, referencePosition, backward)
                    : matchChainedSubSequence(region, sub, cursor);

            if (result < 0) {
                System.out.println("      -> NO valid candidate found. FAIL here.");
                return false;
            }
            System.out.printf("      -> matched, chain ends at %,d%n", result);
            cursor = result;
            first = false;
        }
        System.out.println("    -> ALL SubSequences in this ByteSequence matched.");
        return true;
    }

    // ------------------------------------------------------------------
    // XML parsing (StAX)
    // ------------------------------------------------------------------

    static List<InternalSignatureDef> parseSignatures(File xmlFile) throws Exception {
        List<InternalSignatureDef> signatures = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (InputStream in = new BufferedInputStream(new FileInputStream(xmlFile))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);

            InternalSignatureDef currentSig = null;
            ByteSequenceDef currentByteSeq = null;
            SubSequenceDef currentSubSeq = null;

            boolean inSequenceTag = false;
            boolean inFragmentTag = false;
            String fragmentSide = null;
            int fragmentPosition = 0, fragmentMin = 0, fragmentMax = 0;
            StringBuilder text = new StringBuilder();

            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "InternalSignature":
                            currentSig = new InternalSignatureDef(Integer.parseInt(r.getAttributeValue(null, "ID")));
                            signatures.add(currentSig);
                            break;
                        case "ByteSequence": {
                            currentByteSeq = new ByteSequenceDef();
                            String ref = r.getAttributeValue(null, "Reference");
                            if ("EOFoffset".equals(ref)) currentByteSeq.reference = Reference.EOF;
                            else if (ref == null || ref.isEmpty()) currentByteSeq.reference = Reference.ANYWHERE;
                            else currentByteSeq.reference = Reference.BOF;
                            String endian = r.getAttributeValue(null, "Endianness");
                            currentByteSeq.littleEndian = "Little-endian".equals(endian);
                            currentSig.byteSequences.add(currentByteSeq);
                            break;
                        }
                        case "SubSequence": {
                            currentSubSeq = new SubSequenceDef();
                            currentSubSeq.position = intAttr(r, "Position", 1);
                            currentSubSeq.minSeqOffset = intAttr(r, "SubSeqMinOffset", 0);
                            currentSubSeq.maxSeqOffset = intAttr(r, "SubSeqMaxOffset", -1);
                            currentByteSeq.subSequences.add(currentSubSeq);
                            break;
                        }
                        case "Sequence":
                            inSequenceTag = true;
                            text.setLength(0);
                            break;
                        case "LeftFragment":
                        case "RightFragment":
                            inFragmentTag = true;
                            fragmentSide = local.startsWith("Left") ? "Left" : "Right";
                            fragmentPosition = intAttr(r, "Position", 0);
                            fragmentMin = intAttr(r, "MinOffset", 0);
                            fragmentMax = intAttr(r, "MaxOffset", 0);
                            text.setLength(0);
                            break;
                        default:
                            break;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && (inSequenceTag || inFragmentTag)) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("Sequence") && inSequenceTag) {
                        inSequenceTag = false;
                        try {
                            List<PatternElement> pattern = parsePattern(text.toString().trim());
                            if (currentByteSeq.littleEndian) pattern = reversed(pattern);
                            currentSubSeq.anchor = pattern;
                        } catch (RuntimeException parseError) {
                            System.err.println("  [skip] Signature " + currentSig.id
                                    + ": could not parse anchor \"" + text + "\" (" + parseError + ")");
                        }
                    } else if ((local.equals("LeftFragment") || local.equals("RightFragment")) && inFragmentTag) {
                        inFragmentTag = false;
                        try {
                            List<PatternElement> pattern = parsePattern(text.toString().trim());
                            if (currentByteSeq.littleEndian) pattern = reversed(pattern);
                            Fragment f = new Fragment(fragmentPosition, fragmentMin, fragmentMax, pattern);
                            if (fragmentSide.equals("Left")) currentSubSeq.leftFragments.add(f);
                            else currentSubSeq.rightFragments.add(f);
                        } catch (RuntimeException parseError) {
                            System.err.println("  [skip] Signature " + currentSig.id
                                    + ": could not parse " + fragmentSide + "Fragment \"" + text
                                    + "\" (" + parseError + ") - signature will not match via this SubSequence");
                            currentSubSeq.anchor = null;
                        }
                    }
                }
            }
            r.close();
        }
        precomputeOrdering(signatures);
        return signatures;
    }

    /**
     * PERFORMANCE: precomputes, once per signature (not once per detect() call),
     * the sorted/possibly-reversed SubSequence order for each ByteSequence, and
     * the Position-grouped fragment lists for each SubSequence.
     *
     * Both used to be recomputed from scratch on every call to
     * matchByteSequence()/verifyRightFragmentChain()/verifyLeftFragmentChain() -
     * i.e. once per signature per file for the first, and once per ANCHOR
     * CANDIDATE TRIED per file for the second (which can be many times per
     * signature within a MAX_ANCHOR_SEARCH_DISTANCE window). Since none of this
     * data changes after parsing, redoing it on every scan was pure repeated
     * allocation/sorting overhead - confirmed as the dominant cost behind a real
     * production report of ~40ms overhead per file regardless of file size or
     * content (an HTML file and a 13x-larger PNG cost almost identically), which
     * a naive per-signature scaling argument (signature_count x window_size)
     * does not explain on its own.
     */
    static void precomputeOrdering(List<InternalSignatureDef> signatures) {
        for (InternalSignatureDef sig : signatures) {
            for (ByteSequenceDef bs : sig.byteSequences) {
                List<SubSequenceDef> ordered = new ArrayList<>(bs.subSequences);
                ordered.sort(Comparator.comparingInt(s -> s.position));
                if (bs.reference == Reference.EOF) Collections.reverse(ordered);
                bs.orderedSubSequences = ordered;

                for (SubSequenceDef sub : bs.subSequences) {
                    sub.groupedRightFragments = groupByPosition(sub.rightFragments);
                    sub.groupedLeftFragments = groupByPosition(sub.leftFragments);
                    if (sub.anchor != null) {
                        sub.anchorForwardShifts = computeForwardShifts(sub.anchor);
                        sub.anchorBackwardShifts = computeBackwardShifts(sub.anchor);
                    }
                    for (Fragment f : sub.rightFragments) f.forwardShifts = computeForwardShifts(f.pattern);
                    for (Fragment f : sub.leftFragments) f.forwardShifts = computeForwardShifts(f.pattern);
                }
            }
        }
    }

    static int intAttr(XMLStreamReader r, String name, int defaultValue) {
        String v = r.getAttributeValue(null, name);
        return (v == null || v.isEmpty()) ? defaultValue : Integer.parseInt(v);
    }

    // ------------------------------------------------------------------
    // FileFormat (PUID) lookup
    // ------------------------------------------------------------------

    static final class FileFormatDef {
        final int id;
        final String puid;
        final String name;
        final String mimeType; // may be null - not every FileFormat entry has one
        final String version; // may be null - not every FileFormat entry has one
        final List<Integer> signatureIds;
        final List<Integer> hasPriorityOverFormatIds;
        FileFormatDef(int id, String puid, String name, String mimeType, String version,
                      List<Integer> signatureIds, List<Integer> hasPriorityOverFormatIds) {
            this.id = id;
            this.puid = puid;
            this.name = name;
            this.mimeType = mimeType;
            this.version = version;
            this.signatureIds = signatureIds;
            this.hasPriorityOverFormatIds = hasPriorityOverFormatIds;
        }
    }

    static List<FileFormatDef> parseFileFormats(File xmlFile) throws Exception {
        List<FileFormatDef> formats = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (InputStream in = new BufferedInputStream(new FileInputStream(xmlFile))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            int id = -1;
            String puid = null, name = null, mimeType = null, version = null;
            List<Integer> sigIds = null;
            List<Integer> priorityOverIds = null;
            boolean inFileFormat = false;
            boolean inSigIdTag = false;
            boolean inPriorityTag = false;
            StringBuilder text = new StringBuilder();

            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("FileFormat")) {
                        inFileFormat = true;
                        id = Integer.parseInt(r.getAttributeValue(null, "ID"));
                        puid = r.getAttributeValue(null, "PUID");
                        name = r.getAttributeValue(null, "Name");
                        mimeType = r.getAttributeValue(null, "MIMEType"); // may be null - not always present
                        version = r.getAttributeValue(null, "Version"); // may be null - not always present
                        sigIds = new ArrayList<>();
                        priorityOverIds = new ArrayList<>();
                    } else if (local.equals("InternalSignatureID") && inFileFormat) {
                        inSigIdTag = true;
                        text.setLength(0);
                    } else if (local.equals("HasPriorityOverFileFormatID") && inFileFormat) {
                        inPriorityTag = true;
                        text.setLength(0);
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && (inSigIdTag || inPriorityTag)) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("InternalSignatureID")) {
                        inSigIdTag = false;
                        sigIds.add(Integer.parseInt(text.toString().trim()));
                    } else if (local.equals("HasPriorityOverFileFormatID")) {
                        inPriorityTag = false;
                        priorityOverIds.add(Integer.parseInt(text.toString().trim()));
                    } else if (local.equals("FileFormat")) {
                        inFileFormat = false;
                        formats.add(new FileFormatDef(id, puid, name, mimeType, version, sigIds, priorityOverIds));
                    }
                }
            }
            r.close();
        }
        return formats;
    }

    /**
     * Applies DROID's priority-resolution rule: if FileFormat A "HasPriorityOverFileFormatID"
     * pointing at FileFormat B, and BOTH matched, B is suppressed - A wins. This is a
     * real, separate stage in DROID's identification pipeline, distinct from raw binary
     * signature matching (see class javadoc) - discovered by noticing this element on
     * real FileFormat entries (e.g. ODP's fmt/1754) and finding it necessary to explain
     * why real DROID reports one PUID even when several raw signatures may have fired.
     *
     * Iterates to a fixpoint in case priority relationships chain (A over B over C).
     */
    static List<FileFormatDef> applyPriorityResolution(List<FileFormatDef> matchedFormats) {
        Set<Integer> survivingIds = new HashSet<>();
        for (FileFormatDef f : matchedFormats) survivingIds.add(f.id);

        Map<Integer, FileFormatDef> byId = new HashMap<>();
        for (FileFormatDef f : matchedFormats) byId.put(f.id, f);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (FileFormatDef f : matchedFormats) {
                if (!survivingIds.contains(f.id)) continue;
                for (int suppressedId : f.hasPriorityOverFormatIds) {
                    if (survivingIds.remove(suppressedId)) {
                        changed = true;
                    }
                }
            }
        }

        List<FileFormatDef> result = new ArrayList<>();
        for (FileFormatDef f : matchedFormats) {
            if (survivingIds.contains(f.id)) result.add(f);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Public instance API.
    //
    // Usage:
    //   DroidSignatureVerifier verifier = new DroidSignatureVerifier(new File("DROID_SignatureFile_V124.xml"));
    //   DetectionResult[] results = verifier.detect(new File("somefile.mp4"));
    //
    // The constructor parses the (potentially large, ~2,000+ signature) XML file
    // ONCE and keeps the parsed structures in instance fields, so a single
    // DroidSignatureVerifier instance can be reused to detect() many target files
    // without re-parsing the signature file every time - the parse step (a few
    // hundred ms to ~1.5s depending on file size) was previously repeated on every
    // single detection when this was a one-shot static method.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // DetectionResult is now its own standalone top-level class (see
    // DetectionResult.java), shared with DroidSignatureAhoCorasickVerifier.
    // ------------------------------------------------------------------

    private final List<InternalSignatureDef> signatures;
    private final List<FileFormatDef> formats;

    /**
     * Parses the given DROID signature file once, keeping the resulting
     * signatures and file-format definitions in this instance for reuse across
     * any number of detect() calls.
     *
     * @param signatureFile the DROID_SignatureFile*.xml to parse
     */
    public DroidSignatureVerifier(File signatureFile) throws Exception {
        System.out.println("Parsing full signature structure (anchors + fragments + endianness): " + signatureFile);
        long t0 = System.nanoTime();
        this.signatures = parseSignatures(signatureFile);
        this.formats = parseFileFormats(signatureFile);
        long t1 = System.nanoTime();
        System.out.printf("  Parsed %,d signatures and %,d file formats in %.1f ms%n",
                signatures.size(), formats.size(), (t1 - t0) / 1e6);
    }

    /**
     * Runs full format detection for one file, using the signature file this
     * instance was constructed with, and returns up to 10 candidate results,
     * best guess first.
     *
     * "Best guess first" means: results surviving priority resolution
     * (applyPriorityResolution - see its javadoc for what that means and its
     * known limits) are listed first, since those are DROID's real mechanism for
     * picking a winner among competing raw matches. Any additional raw matches
     * that got suppressed by priority resolution are appended after, as lower-
     * confidence "also matched, but a higher-priority format won" candidates -
     * NOT a confidence-ranked list beyond the resolved/suppressed split, since
     * this class has no further ranking signal (DROID's own reports typically
     * expect at most one winner; showing up to 10 here is a deliberate widening
     * for exploratory/debugging use, not something DROID itself does).
     *
     * Retains the existing System.out progress/diagnostic logging from the
     * original main() (verification time, raw matched IDs) - this is intentional
     * for now per request; a future version may want a quiet mode.
     *
     * @param targetFile the file to identify
     * @return array of 0 to 10 DetectionResults, index 0 = top candidate
     */
    public DetectionResult[] detect(File targetFile) throws Exception {
        FileRegion region = readBoundedRegion(targetFile);
        if (VERBOSE) System.out.println("\nVerifying against: " + targetFile + " (" + region.length + " bytes)");
        return detectFromRegion(region);
    }

    /**
     * Size threshold below which detect(File) just reads the whole file in one
     * go (simple, and reading a file we already know the exact size of is cheap
     * either way) - above this, only bounded head+tail windows are read. Chosen
     * generously above any file size likely to cause real memory pressure, while
     * being well below the ~2GB hard limit where Files.readAllBytes stops working
     * at all (Java arrays are int-indexed).
     */
    static long LARGE_FILE_THRESHOLD_BYTES = 100_000_000L; // 100 MB

    /**
     * Size of the head and tail windows read for files above
     * LARGE_FILE_THRESHOLD_BYTES. Generously larger than any real
     * MAX_ANCHOR_SEARCH_DISTANCE / SubSeqMaxOffset value seen in real signatures
     * so far (the largest found across the whole investigation was ~136,000 bytes,
     * for a DMG hybrid disk image signature) - 20 MB leaves a huge safety margin
     * while keeping total memory use for even a many-GB file at ~40 MB.
     */
    static int WINDOW_BYTES_FOR_LARGE_FILES = 20_000_000; // 20 MB

    /**
     * Reads a FileRegion for the given file: the whole file if it's at or below
     * LARGE_FILE_THRESHOLD_BYTES, or bounded head+tail windows (via a seekable
     * RandomAccessFile - no need to read the middle at all) if it's larger. This
     * is what actually fixes the OutOfMemoryError this method used to throw on
     * multi-GB files (see class javadoc "OutOfMemoryError" note and FileRegion's
     * own javadoc for the full rationale).
     */
    static FileRegion readBoundedRegion(File file) throws IOException {
        long length = file.length(); // cheap - just filesystem metadata, no content read
        if (length <= LARGE_FILE_THRESHOLD_BYTES) {
            byte[] all = Files.readAllBytes(file.toPath());
            return new FileRegion(all, null, all.length);
        }
        int windowSize = WINDOW_BYTES_FOR_LARGE_FILES;
        byte[] head = new byte[windowSize];
        byte[] tail = new byte[windowSize];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(0);
            raf.readFully(head);
            raf.seek(length - windowSize);
            raf.readFully(tail);
        }
        if (VERBOSE) {
            System.out.printf("  Large file (%,d bytes) - reading bounded head+tail windows (%,d bytes each)"
                            + " instead of the whole file (see FileRegion's javadoc for why this is safe).%n",
                    length, windowSize);
        }
        return new FileRegion(head, tail, length);
    }

    /**
     * Same as detect(File), but reads from an InputStream instead - e.g. for use
     * inside a pipeline (like warc-indexer's own WARCPayloadAnalyzers, which is
     * where this whole investigation started) where the payload is already
     * available as a stream rather than a standalone file on disk.
     *
     * If the stream supports mark/reset (InputStream.markSupported()), this
     * attempts stream.reset() before reading, on the assumption that an earlier
     * step (e.g. Tika's own MIME/format detection, which typically peeks at the
     * first few KB via mark/reset and is expected to leave the stream positioned
     * back at the start afterward) may have already consumed some of the stream.
     * If the stream doesn't support mark/reset, this reads from wherever the
     * stream's current position happens to be - there is no general way to
     * "rewind" an arbitrary InputStream that never had mark() called on it, so
     * the caller is responsible for supplying a stream positioned where they want
     * reading to start if mark/reset isn't available (e.g. TikaInputStream, which
     * supports mark() with an effectively unlimited read limit, is a good fit for
     * this method's needs in a Tika-based pipeline).
     *
     * Unlike detect(File), the total length isn't known in advance for an
     * arbitrary stream, so this reads a bounded head+tail using a streaming
     * approach instead (see readBoundedRegion(InputStream)) - it never buffers
     * the whole stream in memory, so this is safe for arbitrarily large streams
     * too, not just files.
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
                // No prior mark() was set, or the stream doesn't actually support
                // resetting despite markSupported() returning true - proceed from
                // wherever the stream currently is rather than failing outright.
                System.out.println("  (reset() failed on the supplied stream, reading from current position: " + e + ")");
            }
        }
        FileRegion region = readBoundedRegion(in);
        if (VERBOSE) System.out.println("\nVerifying against: <input stream> (" + region.length + " bytes)");
        return detectFromRegion(region);
    }

    /**
     * Reads a FileRegion from a stream whose total length isn't known in advance,
     * WITHOUT ever buffering the whole thing in memory - safe for arbitrarily
     * large streams, same goal as readBoundedRegion(File) but via streaming
     * instead of seeking (an arbitrary InputStream generally can't seek).
     *
     * Strategy: fill a head buffer of WINDOW_BYTES_FOR_LARGE_FILES bytes first.
     *   - If the stream ends before that buffer fills, it was a small stream -
     *     trim it to the actual length and return it as the whole (head-only)
     *     region, no tail needed.
     *   - If the stream continues beyond the head window, keep reading into a
     *     fixed-size CIRCULAR tail buffer (overwriting the oldest bytes as new
     *     ones arrive), tracking total length, until EOF - so memory use stays
     *     bounded at 2 x windowSize regardless of how long the stream actually is.
     */
    static FileRegion readBoundedRegion(InputStream in) throws IOException {
        int windowSize = WINDOW_BYTES_FOR_LARGE_FILES;
        byte[] headBuffer = new byte[windowSize];
        int headFilled = 0;
        int n;
        while (headFilled < windowSize && (n = in.read(headBuffer, headFilled, windowSize - headFilled)) != -1) {
            headFilled += n;
        }
        if (headFilled < windowSize) {
            // Stream ended within the head window - a small stream, we have it all.
            byte[] all = Arrays.copyOf(headBuffer, headFilled);
            return new FileRegion(all, null, all.length);
        }

        // Stream is larger than the head window - keep reading, maintaining a
        // circular tail buffer of the last `windowSize` bytes seen, until EOF.
        //
        // PERFORMANCE: fills the circular buffer using bulk System.arraycopy
        // calls (at most 2 per incoming chunk - one before the wrap point, one
        // after, if the chunk happens to cross it), instead of a per-byte loop
        // with a modulo operation on every single byte. That per-byte version
        // was found to be a real, measurable cost in production: a real 39MB
        // MP4 spent ~95ms of its ~136ms total detect(InputStream) time outside
        // the actual signature verification step, almost entirely in this read
        // step - confirmed by comparing against real DROID's own timing, which
        // does this same "read past the cache" work far faster. System.arraycopy
        // is typically a highly-optimized native memmove, dramatically faster
        // than a Java-level per-byte copy loop, especially once a modulo
        // operation (one of the slower integer operations) is involved on every
        // single byte rather than once per chunk.
        byte[] tailBuffer = new byte[windowSize];
        long totalLength = headFilled;
        int tailPos = 0;
        boolean tailWrapped = false;
        byte[] chunk = new byte[65536];
        while ((n = in.read(chunk)) != -1) {
            int remaining = n;
            int chunkOffset = 0;
            while (remaining > 0) {
                int spaceUntilWrap = windowSize - tailPos;
                int toCopy = Math.min(remaining, spaceUntilWrap);
                System.arraycopy(chunk, chunkOffset, tailBuffer, tailPos, toCopy);
                tailPos += toCopy;
                chunkOffset += toCopy;
                remaining -= toCopy;
                if (tailPos == windowSize) {
                    tailPos = 0;
                    tailWrapped = true;
                }
            }
            totalLength += n;
        }

        byte[] tail;
        if (!tailWrapped) {
            tail = Arrays.copyOf(tailBuffer, tailPos); // never wrapped - straightforward prefix
        } else {
            tail = new byte[windowSize];
            System.arraycopy(tailBuffer, tailPos, tail, 0, windowSize - tailPos);
            System.arraycopy(tailBuffer, 0, tail, windowSize - tailPos, tailPos);
        }
        if (VERBOSE) {
            System.out.printf("  Large stream (%,d bytes) - reading bounded head+tail windows (%,d bytes each)"
                            + " instead of buffering the whole stream.%n",
                    totalLength, windowSize);
        }
        return new FileRegion(headBuffer, tail, totalLength);
    }

    /**
     * The actual detection logic, shared by both detect(File) and
     * detect(InputStream) - everything downstream of "we now have a FileRegion"
     * lives here exactly once.
     */
    private DetectionResult[] detectFromRegion(FileRegion region) throws Exception {
        long t2 = System.nanoTime();
        List<Integer> matchedSignatureIds = new ArrayList<>();
        for (InternalSignatureDef sig : signatures) {
            if (matchSignature(region, sig)) {
                matchedSignatureIds.add(sig.id);
            }
        }
        long t3 = System.nanoTime();
        if (VERBOSE) {
            System.out.printf("  Verification of all %,d signatures completed in %.1f ms (no hang, no backtracking)%n",
                    signatures.size(), (t3 - t2) / 1e6);
        }

        if (matchedSignatureIds.isEmpty()) {
            if (VERBOSE) System.out.println("  No signatures matched.");
            return new DetectionResult[0];
        }

        List<FileFormatDef> matchedFormats = new ArrayList<>();
        for (FileFormatDef fmt : formats) {
            for (int sigId : fmt.signatureIds) {
                if (matchedSignatureIds.contains(sigId)) {
                    matchedFormats.add(fmt);
                    break;
                }
            }
        }
        if (VERBOSE) {
            System.out.println("  Raw matched signature IDs: " + matchedSignatureIds
                    + " (" + matchedFormats.size() + " format(s) before priority resolution)");
        }

        List<FileFormatDef> resolved = applyPriorityResolution(matchedFormats);
        if (VERBOSE) {
            System.out.println("  After priority resolution:");
            for (FileFormatDef fmt : resolved) {
                System.out.println("    -> " + fmt.puid + "  " + fmt.name);
            }
        }

        // Build the final ordered list: priority-resolution survivors first (best
        // guess), then any suppressed raw matches after, capped at 10 total.
        List<FileFormatDef> ordered = new ArrayList<>(resolved);
        for (FileFormatDef fmt : matchedFormats) {
            if (!resolved.contains(fmt)) {
                ordered.add(fmt);
            }
        }

        int count = Math.min(10, ordered.size());
        DetectionResult[] results = new DetectionResult[count];
        for (int i = 0; i < count; i++) {
            FileFormatDef fmt = ordered.get(i);
            results[i] = new DetectionResult(fmt.puid, fmt.name, fmt.mimeType, fmt.version);
        }
        return results;
    }

    // ------------------------------------------------------------------
    // No main() method in this class - see project convention: this class is a
    // library component, constructed and called from other code (e.g. a folder
    // scanner, a test harness, or a real pipeline integration), not run directly.
    // matchByteSequenceDebug() remains available as a package-visible utility for
    // tracing one signature's matching attempt step by step, callable from a
    // separate test/driver class if needed.
    // ------------------------------------------------------------------
}