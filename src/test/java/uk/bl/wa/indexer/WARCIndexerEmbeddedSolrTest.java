/**
 * 
 */
package uk.bl.wa.indexer;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactoryConfigurationError;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.ArchiveRecord;
import org.archive.util.SurtPrefixSet;
import org.archive.wayback.exception.ResourceNotAvailableException;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import uk.bl.wa.annotation.Annotations;
import uk.bl.wa.annotation.AnnotationsTest;
import uk.bl.wa.annotation.Annotator;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.Normalisation;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 * FIXME - Ignoring these tests as they have problems running under older versions.
 */
@Ignore
public class WARCIndexerEmbeddedSolrTest {

    private String testWarc = getClass().getClassLoader().getResource(
            "wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz")
            .getPath();
    //private String testWarc = "src/test/resources/wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc";
    //private String testWarc = "src/test/resources/variations.warc.gz";
    //private String testWarc = "src/test/resources/TEST.arc.gz";
    
    private EmbeddedSolrServer server;

    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
        
        // Note that the following property could be set through JVM level arguments too
        String solrXmlPath = getClass().getClassLoader()
                .getResource("solr7/solr.xml").getPath();
        Path solrHome = new File(solrXmlPath).getParentFile().toPath();
        System.setProperty("solr.solr.home", solrHome.toString());
        System.setProperty("solr.data.dir", "target/solr-test-home");
        System.setProperty("solr.lock.type", "native");
        System.out.println("Loading container...");
        System.out.println("Setting up embedded server...");
        server = new EmbeddedSolrServer(solrHome, "discovery");
        // Remove any items from previous executions:
        server.deleteByQuery("*:*");
    }

    /**
     * @throws java.lang.Exception
     */
    @After
    public void tearDown() throws Exception {
        server.close();
    }

    /**
     * 
     * @throws SolrServerException
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws TransformerFactoryConfigurationError
     * @throws TransformerException
     * @throws ResourceNotAvailableException 
     */
    @Test
    public void testEmbeddedServer() throws SolrServerException, IOException, NoSuchAlgorithmException, TransformerFactoryConfigurationError, TransformerException, ResourceNotAvailableException {
        // Fire up a SOLR:
        String url = "http://www.lafromagerie.co.uk/cheese-room/?milk=buffalo&amp%3Bamp%3Bamp%3Bamp%3Borigin=wales&amp%3Bamp%3Bamp%3Borigin=switzerland&amp%3Bamp%3Borigin=germany&amp%3Bstyle=semi-hard&style=blue";
        SolrInputDocument document = new SolrInputDocument();
        document.addField("id", "1");
        document.addField("title", "my title");
        document.addField( "url", url );
        document.addField("source_file", "source_file");

        System.out.println("Adding document: "+document);
        server.add(document);
        server.commit();
        
        System.out.println("Querying for document...");
        SolrParams params = new SolrQuery("title:title");
        QueryResponse response = server.query(params);
        assertEquals(1L, response.getResults().getNumFound());
        assertEquals("1", response.getResults().get(0).get("id"));

        // Check that URLs are encoding correctly.
        assertEquals( url, document.getFieldValue( "url" ) );
        System.out.println(response.getResults().get(0).get("url"));
        assertEquals(url, response.getResults().get(0).get("url"));
        
        //  Now generate some Solr documents from WARCs:
        List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();

        // Fire up the indexer:
        WARCIndexer windex = new WARCIndexer();
        // Add the annotator:
        Annotations annotations = Annotations
                .fromJsonFile(AnnotationsTest.ML_ANNOTATIONS_PATH);
        SurtPrefixSet oaSurts = Annotator
                .loadSurtPrefix(AnnotationsTest.ML_OASURTS_PATH);
        windex.setAnnotations(annotations, oaSurts);
        // Prevent the indexer from attempting to query Solr:
        windex.setCheckSolrForDuplicates(false);
        
        File inFile = new File(testWarc);
        ArchiveReader reader = ArchiveReaderFactory.get(inFile);
        Iterator<ArchiveRecord> ir = reader.iterator();
        while( ir.hasNext() ) {
            ArchiveRecord rec = ir.next();
            SolrRecord doc = windex.extract(inFile.getName(), rec);
            if( doc != null ) {
                //System.out.println(doc.toXml());
                //break;
                docs.add(doc.getSolrDocument());
            } else {
                System.out.println("Got a NULL document for " + rec.getHeader().getMimetype() + ": "
                                   + Normalisation.sanitiseWARCHeaderValue(rec.getHeader().getUrl()));
            }
            //System.out.println(" ---- ---- ");
        }
        System.out.println("Added "+docs.size()+" docs.");
        // Check the read worked:
        assertEquals(39L, docs.size());

        server.add(docs);
        server.commit();

        // Now query:
        params = new SolrQuery("content_type:image*");
        //params = new SolrQuery("generator:*");
        response = server.query(params);
        for( SolrDocument result : response.getResults() ) {
            for( String f : result.getFieldNames() ) {
                System.out.println(f + " -> " + result.get(f));
            }
        }
        assertEquals(21L, response.getResults().getNumFound());

        //Test source_file and source_file_path
        SolrDocument doc = response.getResults().get(0);       
        assertEquals(doc.getFieldValue(SolrFields.SOURCE_FILE),"flashfrozen-jwat-recompressed.warc.gz");
        String source_file_path = (String) doc.getFieldValue(SolrFields.SOURCE_FILE_PATH);
        assertTrue(source_file_path.endsWith("wikipedia-mona-lisa/flashfrozen-jwat-recompressed.warc.gz")); //First path of path depend on project location.               
       
    }

}
