package uk.bl.wa.droidlight;

/**
 * FormatInfo - one candidate file format identified by FallbackFormatDetector:
 * PUID, human-readable name, MIME type, and version - straight from the
 * signature file's FileFormat entry, same fields DetectionResult carries for
 * a real binary-signature match (mimeType/version may be empty - not every
 * tentative FileFormat entry declares them, though a real check found 280 of
 * 560 tentative formats DO have a genuine Version attribute - this isn't rare
 * edge-case data).
 *
 * A standalone top-level class (same pattern as DetectionResult, used by
 * DroidSignatureVerifier/DroidSignatureAhoCorasickVerifier/
 * DroidSignatureVerifierHeuristic) rather than nested inside
 * FallbackFormatDetector, since it's a plain data-holder type that doesn't
 * need to live inside the class that produces it.
 */
public final class FormatInfo {
    public final String puid;
    public final String name;
    public final String mimeType;
    public final String version; // e.g. "97-2003" - may be null, not every FileFormat entry has one

    public FormatInfo(String puid, String name, String mimeType, String version) {
        this.puid = puid;
        this.name = name;
        this.mimeType = mimeType;
        this.version = version;
    }

    public String getPuid() {
        return puid;
    }

    public String getName() {
        return name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getVersion() {
        return version;
    }

    /**
     * A single, always-parseable MIME type - same purpose and behavior as
     * DetectionResult.getPrimaryMimeType() (see that class for the full
     * rationale and the shared override table it uses). Returns mimeType
     * unchanged if it has no comma, the curated override if one exists,
     * otherwise the first declared value as a default. Returns null if
     * there's no mimeType at all.
     */
    public String getPrimaryMimeType() {
        if (mimeType == null || mimeType.isEmpty()) return null;
        if (mimeType.indexOf(',') < 0) return mimeType;
        String override = DetectionResult.PRIMARY_MIME_TYPE_OVERRIDES.get(mimeType);
        if (override != null) return override;
        return mimeType.split(",")[0].trim();
    }

    /**
     * getPrimaryMimeType() combined with version - same purpose and behavior
     * as DetectionResult.getPrimaryMimeTypeWithVersion() (see that class).
     */
    public String getPrimaryMimeTypeWithVersion() {
        String primary = getPrimaryMimeType();
        if (primary == null) return null;
        if (version == null || version.isEmpty()) return primary;
        return primary + "; version=" + version;
    }

    /**
     * The MIME type combined with its version, in the exact same format
     * DetectionResult.getMimeTypeWithVersion() produces (see that class) - so
     * a caller can format a result the same way regardless of whether it came
     * from binary-signature matching or from this class's extension/MIME-type
     * fallback. E.g. "text/html; version=5", or just "text/html" when there's
     * no version, or null if there's no mimeType at all.
     *
     * Also matches DetectionResult's handling of a comma-joined MIMEType
     * attribute value (a real PRONOM pattern, e.g. WebP/ICO entries) - applies
     * the version suffix to EACH comma-separated part individually rather than
     * once to the whole joined string, so the result is unambiguous.
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
        return puid + "  " + name + (mime != null ? "  [" + mime + "]" : "");
    }
}