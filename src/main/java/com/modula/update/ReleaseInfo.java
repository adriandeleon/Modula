package com.modula.update;

/**
 * A published release.
 *
 * @param version the tag with any leading {@code v} removed, so it compares as a version
 * @param url where a human goes to get it
 */
public record ReleaseInfo(String version, String url) {

    public ReleaseInfo {
        version = version == null ? "" : version.strip();
        url = url == null ? "" : url.strip();
    }
}
