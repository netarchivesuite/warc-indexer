package uk.bl.wa.droidlight;
/**
 * FormatInfo - one candidate file format identified by FileExtensionFormatDetector:
 * PUID, human-readable name, and MIME type (which may be empty - not every
 * extension-only "tentative format" entry in the DROID signature file declares one).
 *
 * A standalone top-level class (same pattern as DetectionResult, used by
 * DroidSignatureVerifier/DroidSignatureAhoCorasickVerifier) rather than nested
 * inside FileExtensionFormatDetector, since it's a plain data-holder type that
 * doesn't need to live inside the class that produces it.
 */
public final class FormatInfo {
    public final String puid;
    public final String name;
    public final String mimeType;

    public FormatInfo(String puid, String name, String mimeType) {
        this.puid = puid;
        this.name = name;
        this.mimeType = mimeType;
    }

    @Override
    public String toString() {
        return puid + "  " + name + (mimeType != null && !mimeType.isEmpty() ? "  [" + mimeType + "]" : "");
    }
}