package uk.bl.wa.droidlight;
/**
 * DetectionResult - one candidate format detection result: PUID ("code"),
 * human-readable format name ("text"), MIME type, and format version - all
 * straight from the signature file's FileFormat entry (MIMEType, Name, and
 * Version attributes respectively; each may be missing - not every FileFormat
 * entry declares all of them).
 *
 * Example: code="fmt/471", text="Hypertext Markup Language",
 * mimeType="text/html", version="5".
 *
 * Shared by both DroidSignatureVerifier and DroidSignatureAhoCorasickVerifier -
 * pulled out to its own top-level class since both classes need to construct
 * and return arrays of this type, and having one of them own it as a nested
 * class meant the other had to reach into it via a slightly awkward
 * "DroidSignatureVerifier.DetectionResult" qualifier for no real reason - the
 * two verifier classes are peers (same detect(File)/detect(InputStream)
 * contract), not one depending on the other's internals.
 *
 * Fields are public and final (so already directly readable), but explicit
 * getters are also provided below for callers that prefer/require accessor
 * methods over direct field access.
 */
public final class DetectionResult {
    public final String code; // PUID, e.g. "fmt/471"
    public final String text; // format name, e.g. "Hypertext Markup Language"
    public final String mimeType; // e.g. "text/html" - may be null, not every FileFormat entry has one
    public final String version; // e.g. "5" - may be null, not every FileFormat entry has one

    /**
     * Explicit, manually-curated overrides for which single MIME type to
     * prefer when a PRONOM entry declares multiple comma-joined alternates
     * (see getPrimaryMimeType()'s javadoc for full context). PRONOM's own
     * declared ordering carries no consistent meaning - it's not always "most
     * specific first" or "most modern first" - so there is no reliable
     * GENERIC rule that predicts the right choice; this is deliberately a
     * small, explicit, extensible table (same design as
     * FallbackFormatDetector.MIME_TYPE_OVERRIDES, for the same reason), added
     * to as real cases surface, rather than an algorithm pretending to solve
     * something that genuinely needs domain knowledge per case.
     *
     * "application/mp4, video/mp4" -> "video/mp4" (fmt/199 MPEG-4 Media File):
     * a real production case - a downstream consumer (real DROID/nanite's own
     * MediaType comparison code) called MediaType.parse() on the raw,
     * comma-joined value, which isn't valid single-MIME-type syntax at all,
     * got null back, and threw a NullPointerException on the very next line.
     * "video/mp4" is also simply the more useful, specific answer for this
     * format in practice - "application/mp4" is the older, generic container
     * type that could in principle also cover audio-only MP4 content.
     *
     * Package-private (not private) so FormatInfo.getPrimaryMimeType() can
     * share this exact table rather than maintaining its own separate copy -
     * a duplicated table would risk silently drifting out of sync the next
     * time a case gets added to only one of the two.
     */
    static final java.util.Map<String, String> PRIMARY_MIME_TYPE_OVERRIDES = new java.util.HashMap<>();
    static {
        PRIMARY_MIME_TYPE_OVERRIDES.put("application/mp4, video/mp4", "video/mp4");
    }

    public DetectionResult(String code, String text, String mimeType, String version) {
        this.code = code;
        this.text = text;
        this.mimeType = mimeType;
        this.version = version;
    }

    public String getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getVersion() {
        return version;
    }

    /**
     * A single, always-parseable MIME type - for callers that need exactly
     * one value and would break on PRONOM's comma-joined multi-alternate
     * format (e.g. code that calls something like Tika's MediaType.parse(),
     * which returns null for a comma-containing string, not a real MIME
     * type). If mimeType has no comma at all, this just returns it unchanged.
     * If it does, this returns the curated override (see
     * PRIMARY_MIME_TYPE_OVERRIDES's javadoc) if one exists for that exact
     * value, otherwise falls back to the FIRST declared value as a sensible,
     * deterministic default. Returns null if there's no mimeType at all.
     *
     * Note: this deliberately drops any version information - use
     * getMimeTypeWithVersion() when you want the full "mimetype; version=X"
     * (or comma-joined multi-value) form instead, e.g. for Solr indexing.
     */
    public String getPrimaryMimeType() {
        if (mimeType == null || mimeType.isEmpty()) return null;
        if (mimeType.indexOf(',') < 0) return mimeType;
        String override = PRIMARY_MIME_TYPE_OVERRIDES.get(mimeType);
        if (override != null) return override;
        return mimeType.split(",")[0].trim();
    }

    /**
     * getPrimaryMimeType() combined with version, e.g. "video/mp4; version=5"
     * - the single-value counterpart to getMimeTypeWithVersion(). Unlike that
     * method, no comma-splitting logic is needed here at all: getPrimaryMimeType()
     * already guarantees a single, unambiguous MIME type, so this just appends
     * the version suffix once. Returns just the primary MIME type (no
     * "; version=") if there's no version, or null if there's no mimeType at all.
     */
    public String getPrimaryMimeTypeWithVersion() {
        String primary = getPrimaryMimeType();
        if (primary == null) return null;
        if (version == null || version.isEmpty()) return primary;
        return primary + "; version=" + version;
    }

    /**
     * The MIME type combined with its version, in the exact format real DROID
     * itself reports (and that warc-indexer needs to index into Solr as
     * content_type_droid) - e.g. "text/html; version=5", or just "text/html"
     * when there's no version, or null if there's no mimeType at all.
     *
     * This is exactly the bracketed part of toString() below, unbracketed -
     * e.g. for a PNG result whose toString() is
     * "fmt/11  Portable Network Graphics  [image/png; version=1.0]",
     * getMimeTypeWithVersion() returns "image/png; version=1.0" on its own.
     *
     * BUG FIX: previously only checked "mimeType == null", not
     * "mimeType.isEmpty()" - a real case surfaced this: FallbackFormatDetector
     * can produce a DetectionResult with mimeType set to an EMPTY STRING (not
     * null - see its own FormatInfo-to-DetectionResult conversion) for a real
     * PRONOM entry that has a Version but no MimeType attribute at all (e.g.
     * x-fmt/45 "Microsoft Word Document Template", version="97-2003", no
     * MimeType declared). The old guard let this through, producing a
     * malformed "; version=97-2003" with no MIME type prefix at all.
     *
     * BUG FIX: a real PRONOM entry can declare MULTIPLE alternate MIME types as
     * one comma-joined attribute value (e.g. fmt/101 "Extensible Markup
     * Language" declares MIMEType="application/xml, text/xml" - the same
     * pattern confirmed earlier for WebP/ICO). The old code appended
     * "; version=X" once to the whole joined string, producing something like
     * "application/xml, text/xml; version=1.0" - genuinely ambiguous about
     * whether the version applies to just the last part or to both, and a
     * caller naively splitting on the first comma would get "application/xml"
     * with no version info at all. Fixed by applying the version suffix to
     * EACH comma-separated part individually - "application/xml; version=1.0,
     * text/xml; version=1.0" - unambiguous, and the first segment alone is
     * already a complete, valid "mimetype; version=X" value on its own.
     */
    public String getMimeTypeWithVersion() {
        if (mimeType == null || mimeType.isEmpty()) return null;
        if (version == null || version.isEmpty()) return mimeType;
        if (mimeType.indexOf(',') < 0) {
            return mimeType + "; version=" + version;
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = mimeType.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts[i].trim()).append("; version=").append(version);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        String mime = getMimeTypeWithVersion();
        return code + "  " + text + (mime != null ? "  [" + mime + "]" : "");
    }
}