# warc-indexer Maintainability Plan

## Goal

The project has new maintainers. This plan aims to make it safe and comfortable for new contributors to change the
code without causing regressions.

## Current state (October 2026)

The code itself is in reasonable shape. The 3.5.3 dependency cleanup is careful and well commented, and `mvn test`
passes locally (87 tests in 19 classes). The weak points are around the code:

- **No CI.** There is no `.github/` directory, so no workflows, no PR template and no Dependabot config. The history
  shows Dependabot commits, so that config was probably lost when the repository moved from ukwa.
- **Output fields are defined in five places that have already drifted apart** (see Phase 2).
- **Large parts of the I/O, hashing and output code have no tests.**
- **Onboarding documentation is missing or out of date.**

## Phase 1: A safety net for pull requests

Do this first. It protects everything that comes after it.

- [X] Add a GitHub Actions workflow that runs `mvn -B verify` on Java 11, 17 and 21. `CHANGES.md` says those are the
      supported versions.
- [ ] Protect `master`: CI must pass and one review is required before merging.
- [ ] Re-add `.github/dependabot.yml` for Maven and GitHub Actions.
- [ ] Add a PR template with a checklist: tests added, `CHANGES.md` updated, schemas updated if fields changed.
- [X] Add `.idea/` and `.DS_Store` to `.gitignore`.

## Phase 2: Regression tests for the indexer's output

The project's real contract is "this WARC record produces this Solr document". Most tests check only a few fields.

### 2.1 Golden-file tests (most valuable single change)

- [X] Run the indexer over the WARC and ARC files already in `src/test/resources`.
- [X] Write each `SolrRecord` out as JSON with sorted keys, and commit the result as expected output, for example
      under `src/test/resources/golden/`.
- [X] Have the test compare actual output with the expected files. Any change in output then shows up as a readable
      diff in the PR, so reviewers can tell intended changes from regressions.
- [X] Add a documented `-Dupdate.golden=true` switch that regenerates the expected files.
- [ ] Add a second golden run with image analysis enabled. It is off in `reference.conf`, so the PDQ, pHash and
      face-detection fields are not covered yet.

### 2.2 Keep the field definitions consistent

Output fields are defined in five places:

- `SolrFields.java`
- the three Solr schemas: `src/main/solr/solr/`, `solr7/` and `solr9/`
- the OpenSearch mapping, `src/main/opensearch/os1/schema.json`

Drift found:

- The OpenSearch mapping is missing 21 fields that solr9 has, including all `image_pdq_*` fields, `image_p_hash`,
  `links_norm` and `crawl_year_month`. They are only indexed because the mapping has `"dynamic": "true"`, so their
  types are guessed.
- JSON file output probably drops newer fields. `SingleFileDocumentConsumer` writes `record.toMemento().toJSON()`,
  and `Memento.java` (915 lines) lists its fields by hand. It has no PDQ fields. This has not been confirmed by
  running it. The golden-file tests serialise the Solr document directly, so they do not cover this path.
- `SolrFields` has constants that are in no schema (`content_type_droid_b` and `content_warning`, both commented
  "does not seem to be used").

Tasks:

- [ ] Add a unit test that every `SolrFields` constant exists in the solr9 schema and in the OpenSearch mapping.
- [ ] Add the missing fields to the OpenSearch mapping with explicit types.
- [ ] Make JSON output use the generic field map instead of `Memento`, or add a test that `Memento` covers every field.
- [ ] Decide whether the legacy `solr/` (Solr 4.10-era) and `solr7/` configs are still supported. Delete them, or
      label them clearly as legacy.
- [ ] Remove the unused `SolrFields` constants, or document why they are kept.

### 2.3 Keep the configuration files consistent

- [ ] Add a test that `conf/config3.conf` parses and has every key in `src/main/resources/reference.conf`.

## Phase 3: Clean up the test suite

- [ ] Deal with test classes that are not tests. Rename them, move them to a `manual` package, or delete them:
  - `ExternalServiceSolrFieldEnricherIntegrationTest` is a `main()` method. It is the only `*IntegrationTest`, so
    the `integration-tests` phase in the pom runs nothing.
  - `DroidManualTest` is also a `main()`.
  - `CollectionsUpdateTest` sits in `src/main`, ships in the jar, and points at a hardcoded `192.168.x` Solr.
- [ ] Pick one JUnit version and document it. Tests currently mix JUnit 3 (`extends TestCase`) and JUnit 4. Moving
      to JUnit 5 can come later.
- [ ] Quieten `src/test/resources/log4j2-test.xml`. A test run currently prints about 6,000 lines of log output,
      which buries failures.
- [ ] Add a JaCoCo coverage report. Report only for now; don't make it fail the build.

## Phase 4: Documentation for newcomers

### README.md

- [ ] Replace the placeholders ("TBA configuration HOW TO", "Things to document").
- [ ] Fix the example jar name, which is still `3.5.2-SNAPSHOT`.
- [ ] State the supported JDKs: 11, 17 and 21. Java 25 is not supported, because Solr 9 does not run on it.
- [ ] Say where new issues go. The README still points to the old ukwa issue tracker. Migrate the open issues, or
      state clearly that new ones go in this repository.

### Configuration

- [ ] Fix `conf/readme.txt` and the Java comments that refer to `Config3.xml`; the file is `config3.conf`.
- [ ] Document which file is the source of truth. `reference.conf` (titled "for unittests") and `conf/config3.conf`
      (titled "3.5.0") have diverged; for example, `onDiskThreshold` is `1000M` in one and `"100M"` in the other.

### CONTRIBUTING.md (new)

- [ ] How to build and run the tests, and which JDK to use.
- [ ] How to add an analyser. Today a new contributor cannot find this chain on their own:
  1. Write the analyser class.
  2. Register it in `META-INF/services`.
  3. Add its config key to `reference.conf` and `config3.conf`.
  4. Add a `SolrFields` constant for each new field.
  5. Add the fields to the solr9 schema and the OpenSearch mapping.
  6. Update the golden files and add a `CHANGES.md` entry.
- [ ] A PR checklist (the same as the PR template).
- [ ] The release process.

### ARCHITECTURE.md (new, short)

- [ ] Describe the pipeline: `WARCIndexerCommand` → `WARCIndexer.extract` → `WARCPayloadAnalysers` /
      `TextAnalysers` → `SolrRecord` → `DocumentConsumer`. The flow diagram in `PARALLELIZATION_IMPLEMENTATION.md`
      can be reused.
- [ ] Explain why the packages are still named `uk.bl.wa`. Keep the names; renaming them would break users' configs.

### Versioning

- [ ] Use `-SNAPSHOT` versions between releases. The pom says `3.5.2` while `CHANGES.md` already has a 3.5.3 entry,
      so a locally built jar can be mistaken for a release.

## Phase 5: Tests for untested code

These classes are not referenced from any test:

| Area | Classes (lines) | Why it matters |
|---|---|---|
| Streams and hashing | `InputStreamUtils` (534), `HashedCachedInputStream` (253), `HashedInputStream` (195), `CachedInputStreamFactory` (152) | They produce the `hash` field used for deduplication. A subtle bug here quietly corrupts indexes. |
| Output | `SolrDocumentConsumer`, `OpensearchDocumentConsumer`, `SingleFileDocumentConsumer`, `MultiFileDocumentConsumer`, `BufferedDocumentConsumer` | Every indexed document goes through one of these. |
| Analysers | `ImageAnalyser`, `PDFAnalyser`, `XMLAnalyser`, `DroidDetectorAnalyser`, `LanguageIdentifier` | The PDQ/pHash and format-detection features are recent and changing. |
| droid-light | About 2,800 lines, one test (`DroidDetectorTest`) | It was rewritten in 3.5.1 to fix hangs. |

- [ ] Hashing: test known payloads against known digests for each `hashStage` setting.
- [ ] Output: test each `DocumentConsumer` against a fake or embedded target.
- [ ] droid-light: test format detection on representative files, and add a timeout test on the file type that used
      to hang.
- [ ] Analysers: add focused tests, especially for image hashing.

## Phase 6: Code hygiene (gradually, no big-bang rewrites)

Fix these when touching nearby code, or as small focused PRs.

### Shared mutable state

This must be fixed before the parallelization plan in `PARALLELIZATION_IMPLEMENTATION.md` is implemented.

- [ ] droid-light has `static` non-final tuning values: `JPEG_ANCHOR_SEARCH_DISTANCE` (`public`),
      `MAX_ANCHOR_SEARCH_DISTANCE`, `VERBOSE`, `LARGE_FILE_THRESHOLD_BYTES`, `WINDOW_BYTES_FOR_LARGE_FILES` and
      `MAX_CANDIDATES_TO_TRY`. Make them `final` or per-instance.
- [ ] `LanguageIdentifier.errors` is a static `String`.
- [ ] `LanguageProfile.useInterleaved` is `public static`.
- [ ] `WARCIndexer.getYearFromDate` is `synchronized`, which points to shared formatter state.

### Error handling and logging

- [ ] Replace the 78 `System.out`/`System.err` calls and 9 `printStackTrace()` calls in main code with SLF4J logging.
- [ ] Review the 30 `catch (Exception)` and `catch (Throwable)` blocks, some of which swallow errors silently.

### Dead code

- [ ] Remove or finish the commented-out AI-metadata block in `TikaPayloadAnalyser` (around lines 440–467) and the
      matching commented-out `SolrFields` constants.
- [ ] Review the 11 `main()` methods in library classes. Keep the real tools (`WARCIndexerCommand`,
      `ConfigPrinter`) and remove or move the rest.
- [ ] Decide whether `AnnotationsFromAct` is still needed. It hardcodes British Library (ACT) endpoints over `http`.

### Formatting

- [ ] Add an `.editorconfig` (4-space indent, UTF-8, LF). Formatting is already mostly consistent; only 2 files mix
      tabs and spaces.
- [ ] If you adopt a formatter such as Spotless, use `ratchetFrom` so only changed lines are reformatted. A whole-repo
      reformat would wreck `git blame`.

## Suggested order

| When | Work |
|---|---|
| Week 1 | Phase 1 (CI, branch protection, Dependabot, `.gitignore`) and the README fixes from Phase 4 |
| Next | Phase 2 (golden-file tests, field and config consistency tests) |
| Then | Phase 3 (test suite cleanup), and CONTRIBUTING.md and ARCHITECTURE.md from Phase 4 |
| Ongoing | Phase 5 (tests for untested code) and Phase 6 (hygiene), as people touch the code |
