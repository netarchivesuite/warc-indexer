package uk.bl.wa.indexer;

/*
 * #%L
 * warc-indexer
 * %%
 * Copyright (C) 2013 - 2026 The webarchive-discovery project contributors
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.solr.common.SolrInputField;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.ArchiveRecord;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.typesafe.config.ConfigFactory;

import uk.bl.wa.solr.SolrRecord;

/**
 * Golden-file regression test for the indexer's output.
 * <p>
 * Each archive in {@link #archives()} is indexed with the default configuration, and every resulting
 * {@link SolrRecord} is written as JSON with sorted keys. The result must match the expected output committed under
 * {@code src/test/resources/golden/}. Any change to what the indexer produces therefore fails this test, and once the
 * expected files are regenerated the change shows up as a readable diff in the pull request.
 * <p>
 * When a change in output is intended, regenerate the expected files, review the diff and commit it with the change:
 * <pre>
 * mvn test -Dtest=WARCIndexerGoldenFileTest -Dupdate.golden=true
 * </pre>
 */
@RunWith(Parameterized.class)
public class WARCIndexerGoldenFileTest {
    private static final Logger log = LoggerFactory.getLogger(WARCIndexerGoldenFileTest.class);

    /** Set this system property to {@code true} to overwrite the expected files with the current output. */
    public static final String UPDATE_PROPERTY = "update.golden";

    private static final Path GOLDEN_DIR = baseDir().resolve(Paths.get("src", "test", "resources", "golden"));
    private static final Path ACTUAL_DIR = baseDir().resolve(Paths.get("target", "golden-actual"));

    private static final ObjectWriter JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writer(new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    @Parameters(name = "{0}")
    public static Collection<Object[]> archives() {
        return Arrays.asList(new Object[][] {
                {"IAH-20080430204825-00000-blackbook-truncated.warc.gz"},
                {"IAH-20080430204825-00000-blackbook-truncated.arc.gz"},
                {"IAH-urls-wget.warc.gz"},
                {"truncated_datetime.warc"},
                {"compression/transfer_compression_brotli.warc.gz"},
                {"gov.uk-revisit-warcs/BL-20140325121225068-00000-32090~opera~8443.warc.gz"},
                {"gov.uk-revisit-warcs/BL-20140325122341434-00000-32090~opera~8443.warc.gz"},
                {"wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz"},
        });
    }

    private final String archive;

    public WARCIndexerGoldenFileTest(String archive) {
        this.archive = archive;
    }

    @Test
    public void outputMatchesGoldenFile() throws Exception {
        String actual = indexToJson(archive);
        Path goldenFile = GOLDEN_DIR.resolve(archive + ".json");

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(goldenFile.getParent());
            Files.write(goldenFile, actual.getBytes(StandardCharsets.UTF_8));
            log.info("Updated golden file {}", goldenFile);
            return;
        }

        assertTrue("Missing golden file " + goldenFile + ". Create it with -D" + UPDATE_PROPERTY + "=true",
                   Files.exists(goldenFile));
        String expected = new String(Files.readAllBytes(goldenFile), StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            Path actualFile = ACTUAL_DIR.resolve(archive + ".json");
            Files.createDirectories(actualFile.getParent());
            Files.write(actualFile, actual.getBytes(StandardCharsets.UTF_8));
            fail(describeMismatch(expected, actual, goldenFile, actualFile));
        }
    }

    /**
     * Indexes every record in the archive and returns the resulting documents as a JSON array.
     * Records that the indexer skips (for which {@code extract} returns {@code null}) are left out.
     */
    private static String indexToJson(String archive)
            throws IOException, NoSuchAlgorithmException, URISyntaxException {
        WARCIndexer indexer = new WARCIndexer(ConfigFactory.load());
        indexer.setCheckSolrForDuplicates(false);

        Path archivePath = Paths.get(WARCIndexerGoldenFileTest.class.getClassLoader().getResource(archive).toURI());
        // Use the bare file name so that the output does not depend on where the project is checked out
        String archiveName = archivePath.getFileName().toString();

        List<Map<String, Object>> docs = new ArrayList<>();
        try (ArchiveReader reader = ArchiveReaderFactory.get(archivePath.toFile())) {
            for (ArchiveRecord record : reader) {
                SolrRecord doc = indexer.extract(archiveName, record);
                if (doc != null) {
                    docs.add(toSortedMap(doc));
                }
            }
        }
        return JSON.writeValueAsString(docs) + "\n";
    }

    private static Map<String, Object> toSortedMap(SolrRecord record) {
        Map<String, Object> fields = new TreeMap<>();
        for (SolrInputField field : record.getSolrDocument()) {
            fields.put(field.getName(), normalise(field.getValue()));
        }
        return fields;
    }

    /**
     * Keeps the order of multi-valued fields, writes dates in a time zone independent form, and makes paths inside
     * the project (such as {@code source_file_path}) relative so the output is the same on every machine.
     */
    private static Object normalise(Object value) {
        if (value instanceof Collection) {
            List<Object> values = new ArrayList<>();
            for (Object v : (Collection<?>) value) {
                values.add(normalise(v));
            }
            return values;
        }
        if (value instanceof Date) {
            return DateTimeFormatter.ISO_INSTANT.format(((Date) value).toInstant());
        }
        if (value instanceof String) {
            return ((String) value).replace(baseDir() + File.separator, "");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    private static String describeMismatch(String expected, String actual, Path goldenFile, Path actualFile) {
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);
        int line = 0;
        while (line < expectedLines.length && line < actualLines.length
               && expectedLines[line].equals(actualLines[line])) {
            line++;
        }
        return "Indexer output for " + goldenFile.getFileName() + " differs from the golden file, first at line "
               + (line + 1) + ":\n"
               + "  expected: " + lineOrEnd(expectedLines, line) + "\n"
               + "  actual:   " + lineOrEnd(actualLines, line) + "\n"
               + "Compare the full output with:\n"
               + "  diff " + baseDir().relativize(goldenFile) + " " + baseDir().relativize(actualFile) + "\n"
               + "If the change is intended, regenerate the golden files with -D" + UPDATE_PROPERTY + "=true"
               + " and commit them.";
    }

    private static String lineOrEnd(String[] lines, int index) {
        return index < lines.length ? lines[index].trim() : "<end of file>";
    }

    /** Maven sets {@code basedir} for forked test JVMs. IDEs normally run tests from the project directory. */
    private static Path baseDir() {
        return Paths.get(System.getProperty("basedir", System.getProperty("user.dir"))).toAbsolutePath();
    }
}
