package uk.bl.wa.annotation;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DateRange: holds a start/end date for mapping Collection timeframes.
 * 
 * Part of the @Annotations data model.
 * 
 * @author rcoram
 * 
 */
public class DateRange {

    @JsonProperty
    protected Date start;

    @JsonProperty
    protected Date end;

    protected DateRange() {
    }

    public DateRange(String start, String end) {
        if (start != null)
            this.start = new Date(Long.parseLong(start) * 1000L);
        else
            this.start = new Date(0L);

        if (end != null)
            this.end = new Date(Long.parseLong(end) * 1000L);
        else {
            this.end = getDistantFutureDate();
        }
    }

    public Date getStart() {
        if (this.start == null) {
            return new Date(0L);
        } else {
            return start;
        }
    }

    public Date getEnd() {
        if (this.end == null) {
            return getDistantFutureDate();
        } else {
            return end;
        }
    }

    public boolean isInDateRange(Date date) {
        // System.err.println("isInDateRange "
        // + (date.after(getStart()) && date.before(getEnd())));
        return (date.after(getStart()) && date.before(getEnd()));
    }

    public String toString() {
        return "[" + start + "," + end + "]";
    }

    private Date getDistantFutureDate() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, 9999);
        calendar.set(Calendar.MONTH, Calendar.DECEMBER);
        calendar.set(Calendar.DAY_OF_MONTH, 30);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        return calendar.getTime();
    }
}
