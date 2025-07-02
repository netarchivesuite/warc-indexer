package uk.bl.wa.solr;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Field content adjuster used by {@link SolrRecord}.
 */
public class FieldAdjuster implements UnaryOperator<String> {
    private final int maxValues;
    private final Function<String, String> inner;
    private final String pipeline;

    public static final FieldAdjuster PASSTHROUGH = new FieldAdjuster(-1, s -> s);

    public FieldAdjuster(int maxValues, Function<String, String> inner) {
        this(maxValues, inner, "N/A");
    }

    public FieldAdjuster(int maxValues, Function<String, String> inner, String pipelineDescription) {
        this.maxValues = maxValues;
        this.inner = inner;
        this.pipeline = pipelineDescription;
    }

    @Override
    public String apply(String s) {
        return maxValues == 0 ? null : inner.apply(s);
    }

    /**
     * @return the maximum allowed valued for the given field.
     */
    public int getMaxValues() {
        return maxValues;
    }

    @Override
    public String toString() {
        return "FieldAdjuster{" +
               "maxValues=" + maxValues +
               ", pipeline=[" + pipeline +
               "]}";
    }
}
