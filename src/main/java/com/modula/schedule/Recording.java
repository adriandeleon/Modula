package com.modula.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * One scheduled recording: a station, a time, and how long for.
 *
 * <p>Either a one-off on a given date, or a weekly repeat on chosen days. A repeat with no days
 * selected never fires, which is the honest reading of "repeat on no days" and avoids inventing an
 * every-day default nobody asked for.
 *
 * @param days the days it repeats on; empty means one-off, and {@code date} is then used
 * @param date the day for a one-off; ignored when {@code days} is non-empty
 */
public record Recording(
        String id,
        String name,
        long frequencyHz,
        String band,
        LocalTime start,
        Duration duration,
        Set<DayOfWeek> days,
        java.time.LocalDate date,
        boolean enabled) {

    public Recording {
        name = name == null ? "" : name.strip();
        band = band == null || band.isBlank() ? "FM" : band.strip();
        days = days == null ? Set.of() : Set.copyOf(days);
        duration = duration == null || duration.isNegative() || duration.isZero() ? Duration.ofMinutes(30) : duration;
    }

    public boolean repeats() {
        return !days.isEmpty();
    }

    /** When this next starts at or after {@code from}, or null when it never will again. */
    public LocalDateTime nextStart(LocalDateTime from) {
        if (!enabled) {
            return null;
        }
        if (!repeats()) {
            if (date == null) {
                return null;
            }
            LocalDateTime at = LocalDateTime.of(date, start);
            return at.isBefore(from) ? null : at;
        }
        // Look a week ahead: a weekly schedule always recurs inside seven days.
        for (int ahead = 0; ahead <= 7; ahead++) {
            LocalDateTime candidate = LocalDateTime.of(from.toLocalDate().plusDays(ahead), start);
            if (!candidate.isBefore(from) && days.contains(candidate.getDayOfWeek())) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether {@code now} falls inside an occurrence — the question the timer actually asks. */
    public boolean isActiveAt(LocalDateTime now) {
        if (!enabled) {
            return false;
        }
        // Check today and yesterday: a recording that begins at 23:30 and runs an hour is still
        // running at 00:15, on a date its own start never mentions.
        for (int back = 0; back <= 1; back++) {
            java.time.LocalDate day = now.toLocalDate().minusDays(back);
            if (repeats() ? days.contains(day.getDayOfWeek()) : day.equals(date)) {
                LocalDateTime began = LocalDateTime.of(day, start);
                if (!now.isBefore(began) && now.isBefore(began.plus(duration))) {
                    return true;
                }
            }
        }
        return false;
    }

    public String describe() {
        String when = repeats()
                ? days.stream()
                        .sorted()
                        .map(d -> d.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()))
                        .reduce((a, b) -> a + " " + b)
                        .orElse("never")
                : (date == null ? "no date" : date.toString());
        return "%s  %s  %d min".formatted(when, start, duration.toMinutes());
    }
}
