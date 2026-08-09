package com.modula.update;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckTest {

    private static final String RELEASE =
            """
            {"tag_name":"v0.4.0","name":"Modula 0.4.0","draft":false,"prerelease":false,
             "html_url":"https://example.invalid/releases/v0.4.0"}
            """;

    @Test
    void readsTheTagAndUrl() {
        ReleaseInfo release = UpdateCheck.parseLatest(RELEASE);
        assertEquals("0.4.0", release.version());
        assertEquals("https://example.invalid/releases/v0.4.0", release.url());
    }

    @Test
    void ignoresDraftsAndPrereleases() {
        assertNull(UpdateCheck.parseLatest(RELEASE.replace("\"draft\":false", "\"draft\":true")));
        assertNull(UpdateCheck.parseLatest(RELEASE.replace("\"prerelease\":false", "\"prerelease\":true")));
    }

    @Test
    void survivesRubbish() {
        assertNull(UpdateCheck.parseLatest(""));
        assertNull(UpdateCheck.parseLatest("not json"));
        assertNull(UpdateCheck.parseLatest("{}"));
        assertNull(UpdateCheck.parseLatest(null));
    }

    /** The whole point of a version comparison: 0.10 is above 0.9, which a string compare gets wrong. */
    @Test
    void comparesNumerically() {
        assertTrue(UpdateCheck.isNewer("0.9.0", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("0.10.0", "0.9.0"));
        assertTrue(UpdateCheck.isNewer("1.2.3", "1.2.4"));
        assertFalse(UpdateCheck.isNewer("1.2.3", "1.2.3"));
    }

    /** A snapshot is behind the release of the same number, so a dev build is told about it. */
    @Test
    void aSnapshotIsOlderThanItsRelease() {
        assertTrue(UpdateCheck.isNewer("0.4.0-SNAPSHOT", "0.4.0"));
        assertFalse(UpdateCheck.isNewer("0.4.0", "0.4.0-SNAPSHOT"));
    }

    @Test
    void differingLengthsCompareByPosition() {
        assertTrue(UpdateCheck.isNewer("1.2", "1.2.1"));
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2"));
    }

    @Test
    void checksAtMostOncePerDay() {
        long now = 1_700_000_000_000L;
        assertTrue(UpdateCheck.isDue(0L, now));
        assertFalse(UpdateCheck.isDue(now - TimeUnit.HOURS.toMillis(3), now));
        assertTrue(UpdateCheck.isDue(now - TimeUnit.HOURS.toMillis(30), now));
    }

    /** A clock moved backwards must not park the check forever. */
    @Test
    void aFutureStampIsDue() {
        long now = 1_700_000_000_000L;
        assertTrue(UpdateCheck.isDue(now + TimeUnit.DAYS.toMillis(400), now));
    }
}
