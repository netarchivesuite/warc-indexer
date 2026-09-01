/**
 * 
 */
package uk.bl.wa.analyser.payload;


/*
 * #%L
 * warc-indexer
 * %%
 * Copyright (C) 2013 - 2025 The webarchive-discovery project contributors
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

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.IOExceptionList;
import org.apache.commons.io.IOIndexedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import org.archive.io.ArchiveRecordHeader;
import org.archive.url.UsableURI;
import org.archive.url.UsableURIFactory;

import com.typesafe.config.Config;

import uk.bl.wa.droidlight.DetectionResult;
import uk.bl.wa.droidlight.DroidSignatureVerifier;
import uk.bl.wa.indexer.HTTPHeader;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.InputStreamUtils;
import uk.bl.wa.util.Instrument;
import uk.bl.wa.util.Normalisation;


/**
 * @author anj/teg
 *
 */
public class DroidDetectorAnalyser extends AbstractPayloadAnalyser {
    private static Logger log = LoggerFactory.getLogger( DroidDetectorAnalyser.class );

    
    // New implementation of droid that does not get stuck. Uses same signature file.    
    private DroidSignatureVerifier droidLight= null;

    private boolean runDroid = true;

    private boolean passUriToFormatTools = false;

    public DroidDetectorAnalyser() {
        String signatureFile="DROID_SignatureFile_V124.xml";
        try {

            //Read from resources in jar file
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(signatureFile)) {
                droidLight = new DroidSignatureVerifier(in);
                log.info("Droid-light initialized. #signatures loaded="+droidLight.getSignatureCount() +" from signature file:"+signatureFile);                          
            }                               
        } catch (Exception e) {
            log.error("Exception during DroidDetector setup.", e);   
            droidLight=null;
        }
        
        Instrument.createSortedStat("WARCPayloadAnalyzers.analyze#droid", Instrument.SORT.avgtime, 5);
    }

    public void configure(Config conf) {
        this.runDroid = conf.getBoolean("warc.index.id.droid.enabled");
        this.passUriToFormatTools = conf.getBoolean("warc.index.id.useResourceURI");
    }

    @Override
    public boolean shouldProcess(String mime) {
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * uk.bl.wa.analyser.payload.AbstractPayloadAnalyser#analyse(org.archive.io.
     * ArchiveRecordHeader, java.io.InputStream, uk.bl.wa.util.solr.SolrRecord)
     */
    @Override
    public void analyse(String source, ArchiveRecordHeader header,HTTPHeader httpHeader,InputStream tikainput, SolrRecord solr) {
        // Also run DROID (restricted range):
        if (droidLight != null && runDroid == true ) {
            final long droidStart = System.nanoTime();
            try {
                if( InputStreamUtils.isEmpty(tikainput)){
                    return; //skip early if empty stream
                }
                             
                // This is not used by droid anymore. But will can be used by tika
                Metadata metadata = new Metadata();
                if (passUriToFormatTools) {
                    UsableURI uuri = UsableURIFactory.getInstance(Normalisation.fixURLErrors(Normalisation.sanitiseWARCHeaderValue(header.getUrl())));
                    // Droid seems unhappy about spaces in filenames, so hack to avoid:
                    String cleanUrl = uuri.getName().replace(" ", "+");
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, cleanUrl);
                }
                           
                String httpHeaderMimeType= httpHeader.getHeader("Content-Type", "");
                           
                //Since http content-type is generally reliable, just testing against signatures for that mimetype will
                //often match and save performance instead of scanning all. If no match all signatures are scanned.
                //css,javascript, robots.txt are not detected. They are all just text types and not in the signature file.
                
                DetectionResult[] detect = new DetectionResult[0];
                DetectionResult droidLightDetection= null;
                
                if (droidLight.getSignatureCountForMimeType( httpHeaderMimeType) > 0) {
                    detect = droidLight.detectCommonMimeTypes(tikainput,  httpHeaderMimeType);                    
                }

                if (detect.length == 0) { //Do full scan if mimetype scan found nothing or mimetype was not in the common list.                    
                    detect = droidLight.detect(tikainput);
                }
                
                if (detect.length >0) {
                    droidLightDetection=detect[0];                    
                }                                      
                                                  
                String version= null;
                if (droidLightDetection != null) { 
                   solr.setField(SolrFields.CONTENT_TYPE_DROID,  droidLightDetection.getPrimaryMimeTypeWithVersion());
                   version=droidLightDetection.getVersion();
                }

                if (version != null) {                   
                    solr.setField(SolrFields.CONTENT_VERSION,  version); //notice can be overwritten later in workflow when comparing to tika.
                }                
                Instrument.timeRel("WARCPayloadAnalyzers.analyze#droid","WARCPayloadAnalyzers.analyze#droid_type="+  droidLightDetection,droidStart);
            }
            catch(IOIndexedException | IOExceptionList  io) {
                //This is to prevent long stacktraces on windows when indexing. Delete temp file/directory can fail because of slow windows filelock
                log.warn("IO exception(ignore if failed to delete temp dir/files on windows):"+io.getMessage());
            } catch (Exception i) {
                // Note that DROID complains about some URLs with an
                // IllegalArgumentException.
                log.error(i + ": " + i.getMessage() + ";dd; " + source + " @"+ header.getOffset(), i);
            }
            Instrument.timeRel("WARCPayloadAnalyzers.analyze#total",  "WARCPayloadAnalyzers.analyze#droid", droidStart);
        }
    }
}
