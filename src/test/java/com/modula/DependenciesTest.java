package com.modula;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the About panel's "built with" list honest.
 *
 * <p>A hand-maintained list of dependencies is a claim, and a claim nothing checks becomes false the
 * first time the build changes. This reads {@code pom.xml} and fails when a shipped artifact is not
 * accounted for, which turns the list into a statement of fact.
 */
class DependenciesTest {

    private record PomDependency(String artifactId, boolean test) {}

    private static List<PomDependency> pomDependencies() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        // Only the <dependencies> block: <plugin> entries also carry artifactId/version.
        int start = pom.indexOf("<dependencies>");
        int end = pom.indexOf("</dependencies>", start);
        assertTrue(start >= 0 && end > start, "pom.xml has no <dependencies> block");
        String block = pom.substring(start, end);

        List<PomDependency> found = new ArrayList<>();
        Matcher m = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL)
                .matcher(block);
        while (m.find()) {
            String body = m.group(1);
            Matcher id = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(body);
            if (id.find()) {
                found.add(new PomDependency(id.group(1).trim(), body.contains("<scope>test</scope>")));
            }
        }
        assertFalse(found.isEmpty(), "parsed no dependencies from pom.xml");
        return found;
    }

    @Test
    void everyShippedDependencyIsListedInAbout() throws IOException {
        Set<String> listed = new HashSet<>();
        AppInfo.dependencies().forEach(d -> listed.addAll(d.artifactIds()));

        List<String> unaccounted = pomDependencies().stream()
                .filter(d -> !d.test())
                .map(PomDependency::artifactId)
                .filter(id -> !listed.contains(id))
                .toList();

        assertTrue(
                unaccounted.isEmpty(),
                "these ship but are not in AppInfo.dependencies(): " + unaccounted
                        + " — add them, so About stays true");
    }

    /** The converse: an entry left behind after a dependency was dropped is equally a lie. */
    @Test
    void nothingIsListedThatIsNoLongerShipped() throws IOException {
        Set<String> shipped = new HashSet<>();
        pomDependencies().stream().filter(d -> !d.test()).forEach(d -> shipped.add(d.artifactId()));
        // JavaFX pulls base and graphics in transitively, so they are legitimately absent from the pom.
        shipped.addAll(List.of("javafx-base", "javafx-graphics"));

        List<String> stale = AppInfo.dependencies().stream()
                .flatMap(d -> d.artifactIds().stream())
                .filter(id -> !shipped.contains(id))
                .toList();

        assertTrue(stale.isEmpty(), "About lists artifacts the build no longer has: " + stale);
    }

    /** Test-scope libraries are not distributed, so naming them in About would be wrong. */
    @Test
    void testOnlyLibrariesAreNotClaimedAsDependencies() throws IOException {
        Set<String> listed = new HashSet<>();
        AppInfo.dependencies().forEach(d -> listed.addAll(d.artifactIds()));
        for (PomDependency d : pomDependencies()) {
            if (d.test()) {
                assertFalse(listed.contains(d.artifactId()), d.artifactId() + " is test scope, not shipped");
            }
        }
    }

    @Test
    void everyEntryNamesItselfAndItsLicence() {
        assertFalse(AppInfo.dependencies().isEmpty());
        for (AppInfo.Dependency d : AppInfo.dependencies()) {
            assertFalse(d.name().isBlank());
            assertFalse(d.license().isBlank(), d.name() + " has no licence");
        }
    }

    /** Versions come from the pom through build-info.properties, never from a constant here. */
    @Test
    void mavenArtifactsCarryAResolvedVersion() {
        for (AppInfo.Dependency d : AppInfo.dependencies()) {
            if (!d.artifactIds().isEmpty()) {
                assertFalse(d.version().isBlank(), d.name() + " has no version");
                assertFalse(d.version().startsWith("${"), d.name() + " version was not filtered");
            }
        }
    }

    @Test
    void theIdentityShownInAboutIsPopulated() {
        assertEquals("MIT", AppInfo.LICENSE);
        assertFalse(AppInfo.AUTHOR.isBlank());
        assertTrue(AppInfo.COPYRIGHT.contains(AppInfo.AUTHOR));
        assertTrue(AppInfo.HOMEPAGE.startsWith("https://"), "homepage: " + AppInfo.HOMEPAGE);
    }
}
