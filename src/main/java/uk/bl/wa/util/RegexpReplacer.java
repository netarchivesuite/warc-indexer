package uk.bl.wa.util;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generic String replacer that uses 0 or more Patterns + Replacements for processing the given Strings.
 *
 * Uses the Typesafe {@link Config} framework for setup.
 */
public class RegexpReplacer implements UnaryOperator<String> {
    private static Logger log = LoggerFactory.getLogger(RegexpReplacer.class );

    public static final String PATTERN_KEY = "pattern";
    public static final String REPLACEMENT_KEY = "replacement";

    public final List<Replacer> replacers;

    /**
     * Create the list of pattern+replacements using the rules under the given key.
     * The patterns should be stored with the key {@link #PATTERN_KEY} and the replacements with
     * {@link #REPLACEMENT_KEY}. The rules will be applied using {@code Pattern.matcher(s).replaceAll}.
     *
     * The rules are taken from the path rulesPath. If there are no rules at the given path, an empty list of rules
     * is created.
     * @param config configuration possibly containing rules for replacement.
     * @param rulesPath the path in config to read the rules from. For URL-rewriting this will be
     *                  {@code myconfig.getConfigList("warc.index.extract.url_rewrite}
     */
    public RegexpReplacer(Config config, String rulesPath) {
        this(config.hasPath(rulesPath) ? config.getConfigList(rulesPath) : null);
    }

    /**
     * Create the list of pattern+replacements using the given rules.
     * The patterns should be stored with the key {@link #PATTERN_KEY} and the replacements with
     * {@link #REPLACEMENT_KEY}. The rules will be applied using {@code Pattern.matcher(s).replaceAll}.
     *
     * The rules are normally derived from the overall configuration using
     * {@code myconfig.getConfigList("warc.index.extract.url_rewrite}.
     * @param rules a list of rules to apply. This can be empty or null.
     */
    public RegexpReplacer(List<? extends Config> rules) {
        log.debug("Creating RegexpReplacer with {} rules", rules == null ? 0 : rules.size());
        replacers = rules == null ? Collections.emptyList() :
                rules.stream().map(Replacer::new).collect(Collectors.toList());
    }

    /**
     * Apply the given pattern+replacement rules to the given String.
     * @param s input String.
     * @return the input string after the chain of pattern+replacements has been applied.
     */
    @Override
    public String apply(String s) {
        for (Replacer replacer: replacers) {
            s = replacer.apply(s);
        }
        return s;
    }

    @Override
    public String toString() {
        return "RegexpReplacer{" + replacers + "}";
    }

    /**
     * Holds compiled Pattern and corresponding replacement.
     */
    private static class Replacer implements UnaryOperator<String> {
        private final Pattern pattern;
        private final String replacement;

        public Replacer(Config config) {
            this(config.getString(PATTERN_KEY), config.getString(REPLACEMENT_KEY));
        }
        public Replacer(String pattern, String replacement) {
            this.pattern = Pattern.compile(pattern);
            this.replacement = replacement;
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "The replacement for pattern '" + pattern + "' was null, which is not supported");
            }
        }

        @Override
        public String apply(String s) {
            return pattern.matcher(s).replaceAll(replacement);
        }

        @Override
        public String toString() {
            return "replace('" + pattern + "', '" + replacement + "')";
        }
    }
}
