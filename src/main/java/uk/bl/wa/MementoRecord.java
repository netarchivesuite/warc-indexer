package uk.bl.wa;

import java.io.Serializable;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Class used to build up metadata about a Memento.
 * Only a few critical fields are explicit, with metadata held in other fields.
 * Only limited metadata types are supported.
 */
public class MementoRecord implements Serializable {    

    private String sourceFilePath;
    
    private long sourceFileOffset;

    private SortedMap<String,Object> metadata = new TreeMap<String,Object>();
    private SortedMap<String,Class> metadataTypes = new TreeMap<String,Class>();

    public MementoRecord() {
    }

    public String getSourceFilePath() {
        return sourceFilePath;
    }


    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }


    public long getSourceFileOffset() {
        return sourceFileOffset;
    }


    public void setSourceFileOffset(long sourceFileOffset) {
        this.sourceFileOffset = sourceFileOffset;
    }

    public void addMetadata(String name, String value) {
        this.metadata.put(name, value);
        this.metadataTypes.put(name, String.class);
    }

    public SortedMap<String, Object> getMetadata() {
        return this.metadata;
    }

    public SortedMap<String, Class> getMetadataTypes() {
        return this.metadataTypes;
    }

}
