package com.modula;

import java.io.InputStream;
import java.util.List;
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

    public static final String AUTHOR = "Adrian De Leon";

    public static final String LICENSE = "MIT";

    public static final String COPYRIGHT = "Copyright \u00a9 2026 " + AUTHOR;

    /**
     * What Modula is built on, for the About panel.
     *
     * <p>Versions are filtered in from {@code pom.xml} rather than written here, so the panel cannot
     * claim a version that is not the one shipped. {@code artifactIds} is what
     * {@code DependenciesTest} checks the pom against: an entry added to the build and forgotten here
     * fails, so the list stays a statement of fact rather than of intent.
     *
     * @param version empty when the thing is not a Maven artifact
     * @param note why it is here, when that is not obvious
     */
    public record Dependency(String name, String version, String license, String note, List<String> artifactIds) {}

    private static final List<Dependency> DEPENDENCIES = List.of(
            new Dependency(
                    "JavaFX",
                    load("javafx.version", ""),
                    "GPLv2 + Classpath Exception",
                    "",
                    List.of("javafx-controls", "javafx-base", "javafx-graphics")),
            new Dependency("AtlantaFX", load("atlantafx.version", ""), "MIT", "", List.of("atlantafx-base")),
            new Dependency(
                    "dbus-java",
                    load("dbus.version", ""),
                    "MIT",
                    "Linux tray",
                    List.of("dbus-java-core", "dbus-java-transport-native-unixsocket")),
            new Dependency("SLF4J", load("slf4j.version", ""), "MIT", "", List.of("slf4j-jdk14")),
            new Dependency("IBM Plex Mono", "", "SIL OFL 1.1", "bundled typeface", List.of()),
            new Dependency("librtlsdr", "", "GPLv2", "the system's own, not bundled", List.of()));

    public static List<Dependency> dependencies() {
        return DEPENDENCIES;
    }

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
