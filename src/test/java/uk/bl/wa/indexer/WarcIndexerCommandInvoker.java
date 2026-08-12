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

        String warc1="/run/media/teg/1TB_SSD/warcs_wac2023/warcfilename-00000.warc.gz";
        String warc2="/run/media/teg/1TB_SSD/warcs/481331-246-20250219033557151-00008-sb-prod-har-002.statsbiblioteket.dk.warc.gz";    
        String warc3="/run/media/teg/1TB_SSD/warcs/ARCHIVEIT-14499-TEST-JOB1351771-0-SEED2310670-20210128013458248-00001-31rehvbo.warc.gz";
        
        long start=System.currentTimeMillis();
        WARCIndexerCommand.main(new String[]{
                "-c", "conf/config3.conf",
                "-s", "http://localhost:8983/solr/netarchivebuilder",
             //  new File(Thread.currentThread().getContextClassLoader().getResource("IAH-20080430204825-00000-blackbook-truncated.warc.gz").getFile()).getAbsolutePath()
                //instead  of above you can use full path to file outside project
                warc2   });
    System.out.println("millis:"+(System.currentTimeMillis()-start));
    }
}
