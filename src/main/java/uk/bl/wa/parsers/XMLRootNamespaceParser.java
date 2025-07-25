/**
 * 
 */
package uk.bl.wa.parsers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.xml.namespace.QName;

import org.apache.tika.detect.XmlRootExtractor;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class XMLRootNamespaceParser extends AbstractParser {

    /** */
    private static final long serialVersionUID = 710873621129254338L;

    /** */
    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.unmodifiableSet(new HashSet<MediaType>(Arrays.asList(
                  MediaType.APPLICATION_XML
            )));

    public static final Property XML_ROOT_NS = Property.internalText("XML_ROOT_NS");

    /* (non-Javadoc)
     * @see org.apache.tika.parser.Parser#getSupportedTypes(org.apache.tika.parser.ParseContext)
     */
    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    /* (non-Javadoc)
     * @see org.apache.tika.parser.Parser#parse(java.io.InputStream, org.xml.sax.ContentHandler, org.apache.tika.metadata.Metadata, org.apache.tika.parser.ParseContext)
     */
    @Override
    public void parse(InputStream stream, ContentHandler handler,
            Metadata metadata, ParseContext context) throws IOException,
            SAXException, TikaException {
        
        QName qname = new XmlRootExtractor().extractRootElement( stream );
        if( qname != null ) {
            if( qname.getNamespaceURI() != null && ( !"".equals( qname.getNamespaceURI().trim() ) ) ) {
                //log.info( "rootXML: " + qname.getLocalPart() + " prefix:" + qname.getPrefix() + " nsURI:" + qname.getNamespaceURI() );
                metadata.set( XML_ROOT_NS, qname.getNamespaceURI().toLowerCase() + "#" + qname.getLocalPart().toLowerCase() );
            }
        }
    }

}
