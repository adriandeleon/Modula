package com.modula.update;

import java.time.Duration;

/**
 * Deciding whether an update exists, and whether it is time to look. Pure — no network, no clock.
 *
 * <p>Parsing is deliberately hand-rolled rather than pulling in a JSON library for three fields: the
 * release payload is read once a day at most and the alternative is a dependency, a module
 * descriptor and a jlink entry for something a regex answers.
 */
public final class UpdateCheck {

    /** Once a day. A radio is not a thing anyone wants nagged about. */
    public static final Duration INTERVAL = Duration.ofDays(1);

    private UpdateCheck() {}

    /**
     * Extracts the release from a GitHub {@code /releases/latest} payload.
     *
     * @return the release, or null for a draft, a pre-release, or anything unparseable — none of
     *     which is an error worth telling a listener about
     */
    public static ReleaseInfo parseLatest(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        if (booleanField(json, "draft") || booleanField(json, "prerelease")) {
            return null;
        }
        String tag = stringField(json, "tag_name");
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String url = stringField(json, "html_url");
        return new ReleaseInfo(normalise(tag), url == null ? "" : url);
    }

    /** Strips a leading {@code v}, so {@code v1.2.0} and {@code 1.2.0} compare equal. */
    public static String normalise(String tag) {
        String t = tag.strip();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    /**
     * Whether {@code candidate} is newer than {@code current}, comparing dotted numbers numerically
     * so 0.10 sorts above 0.9 — which a string comparison gets backwards.
     *
     * <p>A pre-release suffix sorts <em>below</em> the plain version, per semver: someone running
     * 0.4.0-SNAPSHOT is running something that precedes 0.4.0 and should be told 0.4.0 exists.
     * Dropping the suffix instead — the obvious reading, since the numbers are equal — makes the
     * check permanently silent on exactly the builds most likely to be out of date.
     */
    public static boolean isNewer(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String left = normalise(current == null ? "" : current);
        String right = normalise(candidate);
        int[] a = parts(left);
        int[] b = parts(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return y > x;
            }
        }
        // Same numbers: a release beats the pre-release of that same number, and nothing else moves.
        return isPreRelease(left) && !isPreRelease(right);
    }

    private static boolean isPreRelease(String version) {
        return version.indexOf('-') >= 0;
    }

    /**
     * Whether enough time has passed to look again.
     *
     * <p>A timestamp in the future counts as due: the clock has moved backwards, and the alternative
     * is a check that never runs again until the date catches up.
     */
    public static boolean isDue(long lastCheckEpochMillis, long nowEpochMillis) {
        if (lastCheckEpochMillis <= 0 || lastCheckEpochMillis > nowEpochMillis) {
            return true;
        }
        return nowEpochMillis - lastCheckEpochMillis >= INTERVAL.toMillis();
    }

    private static int[] parts(String version) {
        String numeric = version.split("[-+]", 2)[0];
        String[] pieces = numeric.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String stringField(String json, String name) {
        int key = json.indexOf('"' + name + '"');
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key);
        int open = json.indexOf('"', colon + 1);
        if (colon < 0 || open < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                value.append(json.charAt(++i));
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }

    private static boolean booleanField(String json, String name) {
        int key = json.indexOf('"' + name + '"');
        if (key < 0) {
            return false;
        }
        int colon = json.indexOf(':', key);
        if (colon < 0) {
            return false;
        }
        return json.regionMatches(true, colon + 1, "true", 0, 4)
                || json.substring(colon + 1, Math.min(json.length(), colon + 8))
                        .trim()
                        .startsWith("true");
    }
}
