package uk.bl.wa.util;

import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstrumentTest extends TestCase {
    private static Logger log = LoggerFactory.getLogger(InstrumentTest.class);

    // Not a real test as it requires visual inspection
    public void testHierarchical() {
        Instrument.clear();
        Instrument.time("top", 123456);
        Instrument.time("top", "mid1", 123457);
        Instrument.time("top", "mid2", 123459);
        Instrument.time("mid1", "bottom", 123460);
        assertTrue("There should be a double indent in\n" + Instrument.getStats(),
                   Instrument.getStats().contains("    "));
        log.info(Instrument.getStats());
    }

    public void testChildTimeSortAndLimit() {
        Instrument.clear();
        Instrument.createSortedStat("top", Instrument.SORT.time, 2);
        Instrument.time("top", 123456);
        Instrument.time("top", "mid1", 925457);
        Instrument.time("top", "mid2", 134459);
        Instrument.time("top", "mid2", 334459);
        Instrument.time("top", "mid3", 145500);
        Instrument.time("top", "mid3", 145500);
        Instrument.time("top", "mid3", 145500);
        assertTrue("Sanity check: The output should contain 'mid2'", Instrument.getStats().contains("mid2"));
        assertFalse("The output should not contain 'mid3' as it is the fastest and the order is 'time' with limit 2",
                    Instrument.getStats().contains("mid3"));
        log.info(Instrument.getStats());
    }

    public void testTimeSortIntOverflow() {
        Instrument.clear();
        Instrument.createSortedStat("top", Instrument.SORT.time, 9999);
        Instrument.setAbsolute("top", "a", 69560000L, 2);
        Instrument.setAbsolute("top", "b", 24590000L, 1);
        Instrument.setAbsolute("top", "c", 16830910000L, 1224);
        Instrument.setAbsolute("top", "d", 12452050000L, 1129);
        log.info(Instrument.getStats());
    }

    public void testChildTimeSortOrder() {
        Instrument.clear();
        Instrument.createSortedStat("top", Instrument.SORT.time, 9999);
        Instrument.time("top", 123456);
        Instrument.time("top", "mid1", 925457);
        Instrument.time("top", "mid2", 134459);
        Instrument.time("top", "mid3", 445500);
        Instrument.time("top", "mid5", 245500);
        
        log.info(Instrument.getStats());
    }

    public void testChildCountSortAndLimit() {
        Instrument.clear();
        Instrument.createSortedStat("top", Instrument.SORT.count, 2);
        Instrument.time("top", 123456);
        Instrument.time("top", "mid1", 925457);
        Instrument.time("top", "mid2", 134459);
        Instrument.time("top", "mid2", 334459);
        Instrument.time("top", "mid3", 145500);
        Instrument.time("top", "mid3", 145500);
        Instrument.time("top", "mid3", 145500);
        assertTrue("Sanity check: The output should contain 'mid2'", Instrument.getStats().contains("mid2"));
        assertFalse("The output should not contain 'mid1': It occurs only once  and the order is 'count' with limit 2",
                    Instrument.getStats().contains("mid1"));
        log.info(Instrument.getStats());
    }

}
