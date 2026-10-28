IMPORTANT!!!
============

This warc-indexer repository has been moved and extracted from the multi maven module project [webarchive-discovery](https://github.com/ukwa/webarchive-discovery) maintained by British Library to [netarchivesuite](https://github.com/netarchivesuite/) maintained by the Royal Danish Library.
The Jira issues for the Warc-indexer has not been moved yet and can be found here: https://github.com/ukwa/webarchive-discovery/issues


WARC Indexer
============

[![DOI](https://zenodo.org/badge/1013711589.svg)](https://doi.org/10.5281/zenodo.18183415)

Latest release 3.5.2: https://github.com/netarchivesuite/warc-indexer/releases/tag/3.5.2

This code runs Apache Tika on WARC and ARC records and extracts suitable metadata for indexing.

It is set up to work with Apache Solr, and our schema is provided in src/main/solr. The tests are able to spin-up an embedded Solr instance to verify the configuration and regression-test the indexer at the query level.

Using this command, it can also builds a suitable command-line tool for generating/posting Solr records from web archive files.

    $ mvn clean install

Which runs like this:

    $ java -jar target/warc-indexer-3.5.2-SNAPSHOT-jar-with-dependencies.jar \
    -s http://localhost:8080/ \
    src/test/resources/wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz

TBA configuration HOW TO.

To print the default configuration:

    $ java -cp target/warc-indexer-3.5.2-SNAPSHOT-jar-with-dependencies.jar uk.bl.wa.util.ConfigPrinter

To override the default with a new configuration:

    $ java -jar target/warc-indexer-3.5.2-SNAPSHOT-jar-with-dependencies.jar -Dconfig.file=new.conf \
    -s http://localhost:8080/ \
    src/test/resources/wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz


Note that this project also contains short ARC and WARC test files, taken from the [warc-test-corpus]

Output regression tests
-----------------------

`WARCIndexerGoldenFileTest` indexes the test archives in `src/test/resources` and compares every resulting document
with the expected output in `src/test/resources/golden/`. If you change what the indexer produces, the test fails and
shows the first differing line. When the change is intended, regenerate the expected files and commit them together
with your change, so reviewers can see the effect in the diff:

    $ mvn test -Dtest=WARCIndexerGoldenFileTest -Dupdate.golden=true

Annotations
-----------

Things to document:

* Annotations format.
* ACT client: uk.bl.wa.annotation.AnnotationsFromAct.main(String[]) > annotations.json
* WARCIndexer, CLI and Hadoop versions.
* Updater version: uk.bl.wa.annotation.Annotator.main(String[])


### License ###
GNU General Public License Version 2
