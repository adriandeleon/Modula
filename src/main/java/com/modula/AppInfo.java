package com.modula;

import java.io.InputStream;
import java.util.Properties;

/**
 * Identity: the single source for the version, shown in About, the window title and the update check.
 *
 * <p>The version comes from {@code pom.xml} through a filtered properties file rather than a constant
 * here, so cutting a release touches the pom and nothing else. A non-Maven run — running straight
 * from an IDE's output directory — falls back rather than failing.
 */
public final class AppInfo {

    public static final String NAME = "Modula";

    public static final String DESCRIPTION = "A broadcast radio receiver for RTL-SDR dongles";

    public static final String VERSION = load("version", "0.0.0-dev");

    /** The build date. Maven stamps a full ISO instant; only the day is worth showing. */
    public static final String BUILD_TIME = day(load("build.time", ""));

    /** Where the update check looks. Empty until the project has a published home. */
    public static final String RELEASES_API = load("releases.api", "");

    public static final String HOMEPAGE = load("homepage", "");

    private AppInfo() {}

    /** Whether this is a development build rather than a cut release. */
    public static boolean isSnapshot() {
        return VERSION.contains("SNAPSHOT") || VERSION.endsWith("-dev");
    }

    private static String day(String instant) {
        int t = instant.indexOf('T');
        return t < 0 ? instant : instant.substring(0, t);
    }

    private static String load(String key, String fallback) {
        try (InputStream in = AppInfo.class.getResourceAsStream("build-info.properties")) {
            if (in == null) {
                return fallback;
            }
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty(key, "").trim();
            // An unfiltered file still carries the ${...} placeholders; treat those as absent.
            return value.isEmpty() || value.startsWith("${") ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }
}
