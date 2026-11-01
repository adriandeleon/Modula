package com.modula;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards what GNOME Software and KDE Discover show for the deb (packaging/linux/README.txt). The
 * package check in CI asserts the built file; these catch the sources drifting from each other, which
 * nothing at build time notices.
 */
class LinuxListingTest {

    private static final Path METAINFO = Path.of("packaging", "linux", "io.github.adriandeleon.Modula.metainfo.xml");

    /** Normalised, because a Windows checkout may carry CRLF line endings. */
    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static String homepage() throws IOException {
        Matcher m = Pattern.compile("<modula.homepage>(.+?)</modula.homepage>").matcher(read(Path.of("pom.xml")));
        assertTrue(m.find(), "no modula.homepage in pom.xml");
        return m.group(1);
    }

    @Test
    void everyInstallerLinksTheHomepageAboutShows() throws IOException {
        String homepage = homepage();
        for (String os : new String[] {"linux", "windows"}) {
            String args = read(Path.of("packaging", os, "jpackage.args"));
            assertTrue(
                    args.contains("--about-url\n" + homepage + "\n"),
                    os + "/jpackage.args must pass --about-url " + homepage + ", the pom's modula.homepage");
        }
        assertTrue(
                read(METAINFO).contains("<url type=\"homepage\">" + homepage + "</url>"),
                "the metainfo's homepage must be the pom's");
    }

    /** Software lists the newest release in the metainfo as the latest, whatever was installed. */
    @Test
    void theListingNamesTheLatestRelease() throws IOException {
        Matcher changelog = Pattern.compile("(?m)^## \\[(\\d+\\.\\d+\\.\\d+)] - (\\d{4}-\\d{2}-\\d{2})$")
                .matcher(read(Path.of("CHANGELOG.md")));
        assertTrue(changelog.find(), "no released version in CHANGELOG.md");
        Matcher metainfo = Pattern.compile("<release version=\"([^\"]+)\" date=\"([^\"]+)\"")
                .matcher(read(METAINFO));
        assertTrue(metainfo.find(), "the metainfo lists no release");
        assertEquals(
                changelog.group(1) + " " + changelog.group(2),
                metainfo.group(1) + " " + metainfo.group(2),
                "add the release to the metainfo's <releases>, newest first, when cutting it");
    }

    @Test
    void theMenuEntryAndTheListingAgree() throws IOException {
        String entry = read(Path.of("packaging", "linux", "Modula.desktop"));
        String metainfo = read(METAINFO);
        assertTrue(
                entry.contains("\nIcon=io.github.adriandeleon.Modula\n")
                        && metainfo.contains("<icon type=\"stock\">io.github.adriandeleon.Modula</icon>"),
                "both must name the themed icon finish-deb.sh installs, not a path under /opt");
        assertTrue(
                entry.lines().noneMatch(l -> l.startsWith("#")),
                "the entry is installed on users' machines, and jpackage substitutes its tokens inside comments too");
        assertTrue(metainfo.contains("<pkgname>modula</pkgname>"), "the listing must name its package");
    }

    @Test
    void theDebIsFinishedAfterJpackageOnLinuxOnly() throws IOException {
        String pom = read(Path.of("pom.xml"));
        int jpackage = pom.indexOf("<id>jpackage</id>");
        int finish = pom.indexOf("<id>finish-deb</id>");
        assertTrue(jpackage >= 0 && finish > jpackage, "finish-deb must be declared after jpackage, its run order");
        assertTrue(pom.contains("<skip>${finishDeb.skip}</skip>"));
        String linux = pom.substring(pom.indexOf("<id>os-linux</id>"));
        assertTrue(
                linux.substring(0, linux.indexOf("</profile>")).contains("<finishDeb.skip>false</finishDeb.skip>"),
                "the Linux profile must run finish-deb");
    }
}
