IMPORTANT!!!
============

This warc-indexer repository has been moved and extracted from the multi maven module project [webarchive-discovery](https://github.com/ukwa/webarchive-discovery) maintained by British Library to [netarchivesuite](https://github.com/netarchivesuite/) maintained by the Royal Danish Librry.

Many third part dependencies updated. Complete rewrite of building the shaded uber-jar.

The warc-indexer.with-dependencies.jar file has been tested to produce identical results as the warc-indexer from BL. But will still require further testing and performance measuring before the next release 
which will be 3.4.0.

The Jira issues for the Warc-indexer has not been moved yet and can be found here: https://github.com/ukwa/webarchive-discovery/issues


WARC Indexer
============



This code runs Apache Tika on WARC and ARC records and extracts suitable metadata for indexing.

It is set up to work with Apache Solr, and our schema is provided in src/main/solr. The tests are able to spin-up an embedded Solr instance to verify the configuration and regression-test the indexer at the query level.

Using this command, it can also builds a suitable command-line tool for generating/posting Solr records from web archive files.

    $ mvn clean install

Which runs like this:

    $ java -jar target/warc-indexer-1.1.1-SNAPSHOT-jar-with-dependencies.jar \
    -s http://localhost:8080/ \
    src/test/resources/wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz

TBA configuration HOW TO.

To print the default configuration:

    $ java -cp target/warc-indexer-1.1.1-SNAPSHOT-jar-with-dependencies.jar uk.bl.wa.util.ConfigPrinter

To override the default with a new configuration:

    $ java -jar target/warc-indexer-1.1.1-SNAPSHOT-jar-with-dependencies.jar -Dconfig.file=new.conf \
    -s http://localhost:8080/ \
    src/test/resources/wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz


Note that this project also contains short ARC and WARC test files, taken from the [warc-test-corpus]

Annotations
-----------

Things to document:

* Annotations format.
* ACT client: uk.bl.wa.annotation.AnnotationsFromAct.main(String[]) > annotations.json
* WARCIndexer, CLI and Hadoop versions.
* Updater version: uk.bl.wa.annotation.Annotator.main(String[])


### License ###
GNU General Public License Version 2
