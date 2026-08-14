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
import org.apache.solr.common.SolrInputField;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import org.archive.io.ArchiveRecordHeader;
import org.archive.url.UsableURI;
import org.archive.url.UsableURIFactory;

import com.typesafe.config.Config;

import uk.bl.wa.droidlight.DetectionResult;
import uk.bl.wa.droidlight.DroidSignatureVerifier;
import uk.bl.wa.droidlight.DroidSignatureVerifierHeuristic;
import uk.bl.wa.droidlight.FallbackFormatDetector;
import uk.bl.wa.indexer.HTTPHeader;
import uk.bl.wa.solr.SolrFields;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.util.InputStreamUtils;
import uk.bl.wa.util.Instrument;
import uk.bl.wa.util.Normalisation;


/**
 * @author anj
 *
 */
public class DroidDetectorAnalyser extends AbstractPayloadAnalyser {
    private static Logger log = LoggerFactory.getLogger( DroidDetectorAnalyser.class );

    
    // New implementation of droid that does not get stuck. Uses same signature file.
    
    private DroidSignatureVerifierHeuristic droidLight= null;
    //This is a fallback for droidLight using MimeType.
    private FallbackFormatDetector fallbackFormatDetector= null;
    private boolean runDroid = true;

    private boolean passUriToFormatTools = false;

    public DroidDetectorAnalyser() {
        // Attempt to set up Droid:
        String signatureFile="DROID_SignatureFile_V124.xml";
        try {

            //Read from resources
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(signatureFile)) {
                droidLight = new DroidSignatureVerifierHeuristic(in);
                log.info("Droid-light initialized. #signatures loaded="+droidLight.getSignatureCount() +" from signature file:"+signatureFile);                          
            }                       
            
        } catch (Exception e) {
            log.error("Exception during DroidDetector setup.", e);   
            droidLight=null;
        }
        // Attempt to set up fallback
        try {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(signatureFile)) {
                fallbackFormatDetector = new  FallbackFormatDetector(in);
                log.info("FallbackFormatDetector initialized. #signatures loaded="+droidLight.getSignatureCount() +" from signature file:"+signatureFile);                          
            }                       
            
        } catch (Exception e) {
            log.error("Exception during DroidDetector setup.", e);   
            fallbackFormatDetector=null;
        }
        
        
        Instrument.createSortedStat("WARCPayloadAnalyzers.analyze#droid",
                Instrument.SORT.avgtime, 5);
    }

    public void configure(Config conf) {
        this.runDroid = conf.getBoolean("warc.index.id.droid.enabled");
        this.passUriToFormatTools = conf
                .getBoolean("warc.index.id.useResourceURI");
 
               
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
                
                /*           
                byte[] peek1 = new byte[8];
                tikainput.mark(8);
                int n1 = tikainput.read(peek1);
                tikainput.reset();
                StringBuilder hex1 = new StringBuilder();
                for (int i = 0; i < n1; i++) hex1.append(String.format("%02X ", peek1[i]));
                log.debug("Bytes seen RIGHT BEFORE dd2.detect(): " + hex1);
*/
                
                // Pass the URL in so DROID can fall back on that:
                Metadata metadata = new Metadata();
                if (passUriToFormatTools) {
                    UsableURI uuri = UsableURIFactory.getInstance(Normalisation.fixURLErrors(Normalisation.sanitiseWARCHeaderValue(header.getUrl())));
                    // Droid seems unhappy about spaces in filenames, so hack to
                    // avoid:
                    String cleanUrl = uuri.getName().replace(" ", "+");
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, cleanUrl);
                }
                // Run Droid:
                            
                String httpHeaderMimeType= httpHeader.getHeader("Content-Type", "");
                
                long dd2Start=System.currentTimeMillis();
                System.out.println("1");
                DetectionResult[] detectResult = droidLight.detect(tikainput,header.getUrl());
                
                //If no result was found above, try use mimetype match as fallback. Will no scan if already has result
                DetectionResult[] detectResultWithfallback = fallbackFormatDetector.withFallback(detectResult,header.getUrl(),httpHeaderMimeType);          
                System.out.println("url:"+header.getUrl());
                System.out.println("minetype http header:"+httpHeaderMimeType);                
                System.out.println("dd2 time:"+(System.currentTimeMillis()-dd2Start));                
                String droidLightDetection=null;
                String version=null;
                if (detectResultWithfallback.length >0) {                
                    droidLightDetection=detectResultWithfallback[0].getPrimaryMimeTypeWithVersion();//Only want one                
                    version=detectResultWithfallback[0].getVersion();
                    System.out.println("with fallback detected:"+droidLightDetection);
                }
                //large dataset has shown doing full scan 'droidLight.rescanLongAnchorSignatures(tikainput)' never
                //found anything new.
                                
                
                if (droidLightDetection != null) { 
                   solr.setField(SolrFields.CONTENT_TYPE_DROID,  droidLightDetection);        
                }
                else {
                    System.out.println(header);
                    System.out.println("NODETECT! for mimetype:"+httpHeaderMimeType); //TODO REMOVE!
                    log.debug("No detection for " + header.getUrl() + " - first bytes: " + InputStreamUtils.peekFirst10K(tikainput));
                    log.debug("First 100 bytes as hex: " + InputStreamUtils.peekFirstBytesAsHex(tikainput, 170));
               
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
                log.error(i + ": " + i.getMessage() + ";dd; " + source + " @"
                        + header.getOffset(), i);
            }
            Instrument.timeRel("WARCPayloadAnalyzers.analyze#total",
                    "WARCPayloadAnalyzers.analyze#droid", droidStart);

        }
    }

}
