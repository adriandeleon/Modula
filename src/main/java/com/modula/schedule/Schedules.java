package com.modula.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reading and writing the schedule file — one tab-separated record per line.
 *
 * <p>The same shape as {@code presets.txt}, and for the same reason: it is a handful of records a
 * person may reasonably want to read or fix in an editor, and a text file makes that possible without
 * a parser between them and it.
 *
 * <p>Lenient per line. A line that will not parse is dropped rather than taking the file with it,
 * because losing one schedule is a much better failure than a radio that will not start.
 */
public final class Schedules {

    private static final String SEPARATOR = "\t";

    private Schedules() {}

    public static List<Recording> parse(String text) {
        List<Recording> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Recording parsed = parseLine(trimmed);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    private static Recording parseLine(String line) {
        String[] f = line.split(SEPARATOR, -1);
        if (f.length < 8) {
            return null;
        }
        try {
            return new Recording(
                    f[0],
                    f[1],
                    Long.parseLong(f[2]),
                    f[3],
                    LocalTime.parse(f[4]),
                    Duration.ofMinutes(Long.parseLong(f[5])),
                    parseDays(f[6]),
                    f[7].isBlank() ? null : LocalDate.parse(f[7]),
                    f.length < 9 || Boolean.parseBoolean(f[8]));
        } catch (RuntimeException e) {
            return null; // one unreadable line, not a broken file
        }
    }

    static Set<DayOfWeek> parseDays(String field) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        if (field == null || field.isBlank()) {
            return days;
        }
        for (String part : field.split(",")) {
            try {
                days.add(DayOfWeek.valueOf(part.strip().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // skip a day name we do not recognise rather than losing the schedule
            }
        }
        return days;
    }

    public static String format(List<Recording> schedules) {
        StringBuilder text = new StringBuilder("# id\tname\tfrequencyHz\tband\tstart\tminutes\tdays\tdate\tenabled\n");
        for (Recording r : schedules) {
            text.append(String.join(
                            SEPARATOR,
                            r.id(),
                            r.name(),
                            Long.toString(r.frequencyHz()),
                            r.band(),
                            r.start().toString(),
                            Long.toString(r.duration().toMinutes()),
                            formatDays(r.days()),
                            r.date() == null ? "" : r.date().toString(),
                            Boolean.toString(r.enabled())))
                    .append('\n');
        }
        return text.toString();
    }

    static String formatDays(Set<DayOfWeek> days) {
        return days.stream()
                .sorted()
                .map(Enum::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
