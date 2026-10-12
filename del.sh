#!/usr/bin/env bash
#
# Removes the legacy Tika-ngram-profile-based language detector from
# warc-indexer. This code was superseded years ago by carrotsearch's
# langid-java (see LanguageAnalyser), which has now itself been replaced
# by Tika's OpenNLP-based detector (tika-langdetect-opennlp). The classes
# below are not referenced from anywhere in src/main or src/test:
#
#   - Not registered in META-INF/services/uk.bl.wa.analyser.text.AbstractTextAnalyser
#     (only LanguageAnalyser, PostcodeAnalyser, FuzzyHashAnalyser and
#     SentimentJTextAnalyser are listed there).
#   - No test class anywhere under src/test references Language, LangId,
#     langid or carrotsearch (checked the full test tree).
#
# So there are no unit test classes or test methods to delete alongside
# this -- it's pure dead code and its supporting resources.
#
# Run this from the root of your warc-indexer checkout (the directory
# that contains pom.xml), on your switch-language-detection-to-tika-nlp
# branch.

set -euo pipefail

if [ ! -f pom.xml ] || ! grep -q "<artifactId>warc-indexer</artifactId>" pom.xml; then
    echo "error: run this from the root of the warc-indexer checkout (pom.xml not found here)" >&2
    exit 1
fi

# Use git rm when inside a git work tree, so the deletions are staged
# for commit; otherwise fall back to plain rm.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    RM="git rm -r --"
    RM_FILE="git rm --"
else
    RM="rm -rv --"
    RM_FILE="rm -v --"
fi

echo "== Removing legacy n-gram-profile language detector classes =="
$RM src/main/java/uk/bl/wa/analyser/text/lang

echo "== Removing its language profile resources =="
$RM src/main/resources/lang-detect-profiles

echo "== Removing the properties files only the legacy detector read =="
$RM_FILE src/main/resources/tika.language.override.properties
$RM_FILE src/test/resources/tika.language.override.properties

echo
echo "Done. Review with 'git status' / 'git diff --cached', then commit."
