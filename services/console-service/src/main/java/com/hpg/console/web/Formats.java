package com.hpg.console.web;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formatting for the templates, reachable as {@code ${@fmt.instant(...)}}.
 *
 * <p>Times are shown in UTC, labelled as such. A payments console that quietly renders a merchant's
 * timestamp in the browser's timezone invites someone to misread a settlement window; stating the zone
 * costs five characters.
 */
@Component("fmt")
public class Formats {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneOffset.UTC);

    public String instant(Instant value) {
        return value == null ? "—" : TIMESTAMP.format(value) + " UTC";
    }

    public String shortInstant(Instant value) {
        return value == null ? "—" : SHORT.format(value);
    }

    /** "in 42 minutes" / "3 minutes ago", for deadlines and retry schedules. */
    public String relative(Instant value) {
        if (value == null) {
            return "—";
        }
        Duration gap = Duration.between(Instant.now(), value);
        boolean future = !gap.isNegative();
        Duration magnitude = gap.abs();
        String amount;
        if (magnitude.toMinutes() < 1) {
            amount = "less than a minute";
        } else if (magnitude.toHours() < 1) {
            amount = plural(magnitude.toMinutes(), "minute");
        } else if (magnitude.toDays() < 1) {
            amount = plural(magnitude.toHours(), "hour");
        } else {
            amount = plural(magnitude.toDays(), "day");
        }
        return future ? "in " + amount : amount + " ago";
    }

    /** A status as a CSS modifier: {@code CAPTURED} becomes {@code captured}. */
    public String slug(String status) {
        return status == null ? "unknown" : status.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
