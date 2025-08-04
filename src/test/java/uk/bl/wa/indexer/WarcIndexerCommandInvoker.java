package uk.bl.wa.indexer;

import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

/** 
 * This class can be used when debugging into a full run of the warc-indexer since it will run in the same JVM.   
 */
public class WarcIndexerCommandInvoker {
    public static void main(String[] args) throws NoSuchAlgorithmException, IOException, TransformerException {
        WARCIndexerCommand.main(new String[]{
                "-c", "conf/config3.conf",
                "-s", "http://localhost:8983/solr/netarchivebuilder",
                new File(Thread.currentThread().getContextClassLoader().getResource("IAH-20080430204825-00000-blackbook-truncated.warc.gz").getFile()).getAbsolutePath()
                //instead  of above you can use full path to file outside project
                //"home/xxx/warcs/warc1.warc.gz"
        });
    }
}
