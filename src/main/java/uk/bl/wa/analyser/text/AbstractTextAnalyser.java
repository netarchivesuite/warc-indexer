/**
 * 
 */
package uk.bl.wa.analyser.text;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

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

import uk.bl.wa.solr.SolrRecord;

/**
 * @author anj
 *
 */
public abstract class AbstractTextAnalyser {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Hook for run-time config.
     *
     * @param conf
     */
    public abstract void configure(Config conf);

    /**
     * Hook allowing a text analyser to opt out of running against this
     * record's extracted text -- e.g. skipping language detection for
     * CSS/JavaScript/JSON, which aren't natural-language text and just
     * introduce noise. Mirrors
     * {@link uk.bl.wa.analyser.payload.AbstractPayloadAnalyser#shouldProcess(String)},
     * but also takes the full {@link SolrRecord}: MIME type alone isn't always
     * enough (e.g. a localisation/resource file served as generic
     * "text/plain"), so an implementation may also want other record fields,
     * such as the URL/file extension.
     * <p>
     * Default: always process. Most text analysers don't care and don't need
     * to override this.
     *
     * @param mimeType the record's normalised MIME type (may be null if unknown)
     * @param solr the record being processed, for any other fields a decision needs
     * @return true if this analyser should run against this record's text
     */
    public boolean shouldProcess(String mimeType, SolrRecord solr) {
        return true;
    }

    /**
     * Sub-classes should implement this method to create text payload annotations for solr.
     *
     * @param text
     * @param solr
     */
    public abstract void analyse( String text, SolrRecord solr );
    
    /**
     * This dynamically loads the available parser implementations on the
     * classpath. Passes in the provided configuration to get things set up.
     * 
     * @return
     */
    public static List<AbstractTextAnalyser> getTextAnalysers(Config conf) {

        // load our plugins
        ServiceLoader<AbstractTextAnalyser> serviceLoader = ServiceLoader
                .load(AbstractTextAnalyser.class);

        // Get the list:
        List<AbstractTextAnalyser> providers = new ArrayList<AbstractTextAnalyser>();
        for (AbstractTextAnalyser provider : serviceLoader) {
            // Perform any necessary configuration:
            provider.configure(conf);
            providers.add(provider);
        }

        return providers;
    }

    /**
     * Just for testing.
     * 
     * @param ignored
     */
    public static void main(String[] ignored) {

        // Get the config:
        Config conf = ConfigFactory.load();

        // create a new provider and call getMessage()
        List<AbstractTextAnalyser> providers = AbstractTextAnalyser
                .getTextAnalysers(conf);
        for (AbstractTextAnalyser provider : providers) {
            System.out.println(provider.getClass().getCanonicalName());
        }
    }
}
