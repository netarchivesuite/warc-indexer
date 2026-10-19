package uk.bl.wa.analyser.payload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tika.metadata.Metadata;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.arc.ARCRecordMetaData;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import uk.bl.wa.indexer.HTTPHeader;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.solr.SolrRecordFactory;

public class TikaPayloadAnalyserTest {

    private static final String URL = "http://example.org/page.html";

    private TikaPayloadAnalyser analyser;

    @Before
    public void setUp() {
        analyser = createAnalyser("");
    }

    /* --- parseLenientIsoDate --- */

    /** A full date-time with a Z offset is parsed as UTC. */
    @Test
    public void testParseFullDateTimeUtc() {
        assertEquals(Instant.parse("2020-05-01T10:11:12Z"),
                TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12Z"));
    }

    /** Offsets written as +hh:mm, +hhmm and +hh are all accepted. */
    @Test
    public void testParseOffsetVariants() {
        Instant expected = Instant.parse("2020-05-01T08:41:12Z");
        assertEquals(expected, TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12+01:30"));
        assertEquals(expected, TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12+0130"));
        assertEquals(Instant.parse("2020-05-01T09:11:12Z"),
                TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12+01"));
        assertEquals(Instant.parse("2020-05-01T15:11:12Z"),
                TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12-05:00"));
    }

    /** Fractional seconds are accepted with either '.' or ',' as the decimal separator. */
    @Test
    public void testParseFractionalSeconds() {
        Instant expected = Instant.parse("2020-05-01T10:11:12.500Z");
        assertEquals(expected, TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12.5Z"));
        assertEquals(expected, TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12,5Z"));
        assertEquals(Instant.parse("2020-05-01T10:11:12.123456789Z"),
                TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12.123456789Z"));
    }

    /** Missing date and time fields default to the start of the period they leave out. */
    @Test
    public void testParseReducedPrecision() {
        assertEquals(local(2020, 1, 1, 0, 0, 0), TikaPayloadAnalyser.parseLenientIsoDate("2020"));
        assertEquals(local(2020, 5, 1, 0, 0, 0), TikaPayloadAnalyser.parseLenientIsoDate("2020-05"));
        assertEquals(local(2020, 5, 1, 0, 0, 0), TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01"));
        assertEquals(local(2020, 5, 1, 10, 0, 0), TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10"));
        assertEquals(local(2020, 5, 1, 10, 11, 0), TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11"));
    }

    /** A value without an offset is interpreted in the JVM default time zone. */
    @Test
    public void testParseWithoutOffsetUsesDefaultZone() {
        assertEquals(local(2020, 5, 1, 10, 11, 12),
                TikaPayloadAnalyser.parseLenientIsoDate("2020-05-01T10:11:12"));
    }

    /** Leading and trailing whitespace around the value is ignored. */
    @Test
    public void testParseTrimsWhitespace() {
        assertEquals(Instant.parse("2020-05-01T10:11:12Z"),
                TikaPayloadAnalyser.parseLenientIsoDate("  2020-05-01T10:11:12Z\n"));
    }

    /** Invalid dates and unsupported ISO 8601 forms throw DateTimeException. */
    @Test
    public void testParseRejectsInvalidValues() {
        String[] invalid = { "2020-13-01", "2020-02-30", "2020-05-01T24:00:00", "2020-5-1",
                "20200501", "2020-W20-1", "2020-138", "2020-05-01 10:11:12", "not a date", "" };
        for (String value : invalid) {
            try {
                TikaPayloadAnalyser.parseLenientIsoDate(value);
                fail("Expected DateTimeException for '" + value + "'");
            } catch (DateTimeException expected) {
                // expected
            }
        }
    }

    /** The output formatter writes UTC without milliseconds. */
    @Test
    public void testIsoDateTimeNoMillisUtcFormat() {
        assertEquals("2020-05-01T08:41:12Z", TikaPayloadAnalyser.ISO_DATE_TIME_NO_MILLIS_UTC
                .format(Instant.parse("2020-05-01T08:41:12.987Z")));
    }

    /* --- addExceptionMetadata --- */

    /** The root cause of a wrapped exception is recorded, not the wrapper. */
    @Test
    public void testAddExceptionMetadataUsesRootCause() {
        Metadata metadata = new Metadata();
        Exception e = new RuntimeException("outer", new IllegalStateException("inner"));
        TikaPayloadAnalyser.addExceptionMetadata(metadata, e);
        assertEquals("java.lang.IllegalStateException: inner",
                metadata.get(TikaPayloadAnalyser.TIKA_PARSE_EXCEPTION));
    }

    /** An exception without a cause is recorded as itself. */
    @Test
    public void testAddExceptionMetadataWithoutCause() {
        Metadata metadata = new Metadata();
        TikaPayloadAnalyser.addExceptionMetadata(metadata, new IOException("boom"));
        assertEquals("java.io.IOException: boom", metadata.get(TikaPayloadAnalyser.TIKA_PARSE_EXCEPTION));
    }

    /* --- SpaceTrimWriter --- */

    /** Runs of spaces and tabs collapse to one space, and runs containing a newline collapse to one newline. */
    @Test
    public void testSpaceTrimWriterCollapsesWhitespace() throws IOException {
        assertEquals("a b\nc d", trimSpaces("a  \t b \n\n  c\td"));
    }

    /** Leading and trailing whitespace is dropped. */
    @Test
    public void testSpaceTrimWriterDropsLeadingAndTrailingWhitespace() throws IOException {
        assertEquals("text", trimSpaces(" \n\t text \n "));
        assertEquals("", trimSpaces(" \n\t "));
    }

    /* --- extract / analyse --- */

    /** shouldProcess accepts every MIME type, since filtering happens after detection. */
    @Test
    public void testShouldProcessAlwaysTrue() {
        assertTrue(analyser.shouldProcess("text/html"));
        assertTrue(analyser.shouldProcess("application/zip"));
        assertTrue(analyser.shouldProcess(null));
    }

    /** Text, title, description, keywords, author, generator and content type are extracted from HTML. */
    @Test
    public void testExtractHtmlTextAndMetadata() throws IOException {
        SolrRecord solr = extract(analyser, html(
                "<title>Test Title</title>"
                + "<meta name=\"description\" content=\"Test description\">"
                + "<meta name=\"keywords\" content=\"alpha, beta\">"
                + "<meta name=\"author\" content=\"Jane Author\">"
                + "<meta name=\"generator\" content=\"TestGen 1.0\">",
                "<p>Hello   world</p>"));

        assertEquals("Hello world", solr.getFieldAsString(SolrFields.SOLR_EXTRACTED_TEXT));
        assertEquals("11", solr.getFieldAsString(SolrFields.SOLR_EXTRACTED_TEXT_LENGTH));
        assertTrue(solr.getFieldAsString(SolrFields.SOLR_CONTENT_TYPE).startsWith("text/html"));
        assertEquals("Test Title", solr.getFieldAsString(SolrFields.SOLR_TITLE));
        assertEquals("Test description", solr.getFieldAsString(SolrFields.SOLR_DESCRIPTION));
        assertEquals("alpha, beta", solr.getFieldAsString(SolrFields.SOLR_KEYWORDS));
        assertEquals("Jane Author", solr.getFieldAsString(SolrFields.SOLR_AUTHOR));
        assertTrue(solr.getFieldAsStrings(SolrFields.GENERATOR).contains("TestGen 1.0"));
        assertFalse(solr.containsKey(SolrFields.SOLR_TIKA_METADATA));
        assertNull(solr.getFieldAsString(SolrFields.PARSE_ERROR));
    }

    /** Content whose detected type is excluded only gets a content type and is not parsed. */
    @Test
    public void testExtractSkipsExcludedMimeType() throws IOException {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i * 7 + 3);
        }
        SolrRecord solr = SolrRecordFactory.createFactory(null).createRecord();
        analyser.extract("test", solr, new ByteArrayInputStream(binary), null);

        assertEquals("application/octet-stream", solr.getFieldAsString(SolrFields.SOLR_CONTENT_TYPE));
        assertFalse(solr.containsKey(SolrFields.SOLR_EXTRACTED_TEXT));
    }

    /** Extracted text is cut off at warc.index.tika.max_text_length characters. */
    @Test
    public void testExtractTruncatesText() throws IOException {
        // Tika's write limit also counts the whitespace it emits outside <body>, so the
        // kept text is a prefix of the body that is at most max_text_length long.
        String body = String.join("", Collections.nCopies(50, "abcdefghij"));
        TikaPayloadAnalyser limited = createAnalyser("warc.index.tika.max_text_length = 100");
        SolrRecord solr = extract(limited, html("", "<p>" + body + "</p>"));

        String text = solr.getFieldAsString(SolrFields.SOLR_EXTRACTED_TEXT);
        assertTrue("Expected some text", text != null && !text.isEmpty());
        assertTrue("Text should be at most 100 chars but was " + text.length(), text.length() <= 100);
        assertTrue("Text should be a prefix of the body", body.startsWith(text));
        assertEquals(Integer.toString(text.length()), solr.getFieldAsString(SolrFields.SOLR_EXTRACTED_TEXT_LENGTH));
    }

    /** The modified date takes precedence over the created date and is written as UTC without millis. */
    @Test
    public void testExtractModifiedDatePreferredOverCreated() throws IOException {
        SolrRecord solr = extract(analyser, html(
                "<meta name=\"dcterms:created\" content=\"2001-02-03T04:05:06Z\">"
                + "<meta name=\"dcterms:modified\" content=\"2019-06-15T10:20:30.123+02:00\">", ""));

        assertEquals("2019-06-15T08:20:30Z", solr.getFieldAsString(SolrFields.LAST_MODIFIED));
        assertEquals("2019", solr.getFieldAsString(SolrFields.LAST_MODIFIED_YEAR));
    }

    /** A date that is not ISO 8601 falls back to Times.extractDate(). */
    @Test
    public void testExtractNonIsoDateFallsBackToTimes() throws IOException {
        SolrRecord solr = extract(analyser, html("<meta name=\"dcterms:modified\" content=\"15/06/2019\">", ""));

        assertEquals("2019", solr.getFieldAsString(SolrFields.LAST_MODIFIED_YEAR));
        assertTrue(solr.containsKey(SolrFields.LAST_MODIFIED));
    }

    /** A date that neither parser understands is left out of the record. */
    @Test
    public void testExtractUnparseableDateIsIgnored() throws IOException {
        SolrRecord solr = extract(analyser, html("<meta name=\"dcterms:modified\" content=\"sometime last year\">", ""));

        assertFalse(solr.containsKey(SolrFields.LAST_MODIFIED));
        assertFalse(solr.containsKey(SolrFields.LAST_MODIFIED_YEAR));
    }

    /** geo:lat and geo:long meta tags are stored as a location. */
    @Test
    public void testExtractGeoLocation() throws IOException {
        SolrRecord solr = extract(analyser, html(geoMeta("55.68", "12.57"), ""));

        assertEquals("55.68,12.57", solr.getFieldAsString(SolrFields.EXIF_LOCATION));
    }

    /** Coordinates outside the valid latitude/longitude range are not stored. */
    @Test
    public void testExtractGeoLocationOutOfRangeIgnored() throws IOException {
        SolrRecord solr = extract(analyser, html(geoMeta("95.0", "12.57"), ""));

        assertFalse(solr.containsKey(SolrFields.EXIF_LOCATION));
    }

    /** Non-numeric coordinates are ignored without failing the extraction. */
    @Test
    public void testExtractGeoLocationNotNumericIgnored() throws IOException {
        SolrRecord solr = extract(analyser, html(geoMeta("north", "east"), "<p>still here</p>"));

        assertFalse(solr.containsKey(SolrFields.EXIF_LOCATION));
        assertEquals("still here", solr.getFieldAsString(SolrFields.SOLR_EXTRACTED_TEXT));
    }

    /** No location is stored when warc.index.tika.extract_exif_location is false. */
    @Test
    public void testExtractGeoLocationDisabled() throws IOException {
        TikaPayloadAnalyser noGeo = createAnalyser("warc.index.tika.extract_exif_location = false");
        SolrRecord solr = extract(noGeo, html(geoMeta("55.68", "12.57"), ""));

        assertFalse(solr.containsKey(SolrFields.EXIF_LOCATION));
    }

    /** With extract_all_metadata enabled, metadata is stored as name=value pairs, minus the excluded names. */
    @Test
    public void testExtractAllMetadata() throws IOException {
        TikaPayloadAnalyser all = createAnalyser("warc.index.tika.extract_all_metadata = true");
        SolrRecord solr = extract(all, html(
                "<title>Test Title</title><meta name=\"generator\" content=\"TestGen 1.0\">", ""));

        List<String> values = solr.getFieldAsStrings(SolrFields.SOLR_TIKA_METADATA);
        assertTrue(values.toString(), values.contains("generator=TestGen 1.0"));
        for (String value : values) {
            assertFalse(value, value.startsWith("dc:title=") || value.startsWith("Content-Type="));
        }
    }

    /** EXIF version and software are extracted from a JPEG. */
    @Test
    public void testExtractJpegExif() throws IOException {
        SolrRecord solr = SolrRecordFactory.createFactory(null).createRecord();
        try (InputStream in = new FileInputStream("src/test/resources/16px-photo.jpg")) {
            analyser.extract("test", solr, in, null);
        }

        assertEquals("image/jpeg", solr.getFieldAsString(SolrFields.SOLR_CONTENT_TYPE));
        assertEquals("2.20", solr.getFieldAsString(SolrFields.EXIF_VERSION));
        assertTrue(solr.getFieldAsStrings(SolrFields.GENERATOR).contains("QuickTime 7.7.1"));
        assertFalse(solr.containsKey(SolrFields.EXIF_LOCATION));
    }

    /** analyse() reads the record stream and fills the Solr record like extract(). */
    @Test
    public void testAnalyse() throws IOException {
        Map<String, Object> core = new HashMap<>();
        core.put("subject-uri", URL);
        core.put("ip-address", "192.168.1.10");
        core.put("creation-date", "20200501101112");
        core.put("content-type", "text/html");
        core.put("length", "0");
        core.put("version", "1");
        core.put("absolute-offset", "0");
        core.put("origin", "");
        ArchiveRecordHeader header = new ARCRecordMetaData("test.arc", core);
        SolrRecord solr = SolrRecordFactory.createFactory(null).createRecord();

        analyser.analyse("test", header, new HTTPHeader(), toStream(html("<title>Analysed</title>", "<p>body</p>")), solr);

        assertEquals("Analysed", solr.getFieldAsString(SolrFields.SOLR_TITLE));
        assertEquals("body", solr.getFieldAsString(SolrFields.SOLR_EXTRACTED_TEXT));
    }

    /* --- helpers --- */

    private static TikaPayloadAnalyser createAnalyser(String overrides) {
        Config config = ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load());
        TikaPayloadAnalyser tika = new TikaPayloadAnalyser();
        tika.configure(config);
        return tika;
    }

    private static SolrRecord extract(TikaPayloadAnalyser tika, String content) throws IOException {
        SolrRecord solr = SolrRecordFactory.createFactory(null).createRecord();
        tika.extract("test", solr, toStream(content), URL);
        return solr;
    }

    private static String html(String head, String body) {
        return "<html><head>" + head + "</head><body>" + body + "</body></html>";
    }

    private static String geoMeta(String lat, String lon) {
        return "<meta name=\"geo:lat\" content=\"" + lat + "\"><meta name=\"geo:long\" content=\"" + lon + "\">";
    }

    private static InputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant local(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZoneId.systemDefault()).toInstant();
    }

    private String trimSpaces(String input) throws IOException {
        StringWriter out = new StringWriter();
        try (Writer writer = analyser.new SpaceTrimWriter(out)) {
            writer.write(input);
        }
        return out.toString();
    }
}
