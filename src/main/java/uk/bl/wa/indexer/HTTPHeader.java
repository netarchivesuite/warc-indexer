package uk.bl.wa.indexer;

import org.apache.commons.httpclient.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

/**
 * Thin wrapper for ArrayList holding HTTP headers and HTTP status.
 */
public class HTTPHeader extends ArrayList<Header> {
    private String httpStatus;
    public HTTPHeader(int initialCapacity) {
        super(initialCapacity);
    }

    public HTTPHeader() {
    }

    public HTTPHeader(@NotNull Collection<? extends Header> c) {
        super(c);
    }

    public String getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(String httpStatus) {
        this.httpStatus = httpStatus;
    }

    public void addAll(Header[] httpHeaders) {
        addAll(Arrays.asList(httpHeaders));
    }
    
    public String getHeader(String key, String defaultValue) {
        key = key.toLowerCase();
        for (Header header: this) {
            if (key.equals(header.getName().toLowerCase())) {
                return header.getValue();
            }
        }
        return defaultValue;
    }
}
