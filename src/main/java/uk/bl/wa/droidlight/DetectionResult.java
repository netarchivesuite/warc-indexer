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
     * The MIME type combined with its version, in the exact format real DROID
     * itself reports (and that warc-indexer needs to index into Solr as
     * content_type_droid) - e.g. "text/html; version=5", or just "text/html"
     * when there's no version, or null if there's no mimeType at all.
     *
     * This is exactly the bracketed part of toString() below, unbracketed -
     * e.g. for a PNG result whose toString() is
     * "fmt/11  Portable Network Graphics  [image/png; version=1.0]",
     * getMimeTypeWithVersion() returns "image/png; version=1.0" on its own.
     */
    public String getMimeTypeWithVersion() {
        if (mimeType == null) return null;
        if (version == null || version.isEmpty()) return mimeType;
        return mimeType + "; version=" + version;
    }

    @Override
    public String toString() {
        String mime = getMimeTypeWithVersion();
        return code + "  " + text + (mime != null ? "  [" + mime + "]" : "");
    }
}