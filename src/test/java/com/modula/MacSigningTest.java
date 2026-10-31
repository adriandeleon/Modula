package com.modula;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the macOS Developer ID signing chain (README, "Signing"). None of it runs off a Mac or outside
 * a release, and each mistake it guards against ships quietly: a jpackage call that never receives the
 * signing options, a signed app that refuses Homebrew's librtlsdr, or a tag that publishes an unsigned
 * dmg.
 */
class MacSigningTest {

    @Test
    void jpackageIsHandedTheSigningOptionsFile() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(
                pom.contains("<jpackage.signArgs>@${project.basedir}/packaging/no-signing.args</jpackage.signArgs>"),
                "every build but a signing release passes the empty options file");
        assertTrue(pom.contains("<argument>${jpackage.signArgs}</argument>"), "the jpackage call no longer takes it");
        String noSigning = Files.readString(Path.of("packaging", "no-signing.args"));
        assertTrue(
                noSigning.lines().allMatch(l -> l.isBlank() || l.startsWith("#")),
                "no-signing.args must stay options-free: it is passed on every platform");
    }

    @Test
    void theSignedAppMayLoadHomebrewsLibrtlsdr() throws IOException {
        String entitlements = Files.readString(Path.of("packaging", "mac", "Modula.entitlements"));
        assertTrue(
                entitlements.contains("com.apple.security.cs.disable-library-validation"),
                "librtlsdr is not signed with our team ID; without this the signed app falls back to rtl_tcp");
        assertTrue(
                entitlements.contains("com.apple.security.cs.allow-jit"), "the JVM's JIT under the hardened runtime");
    }

    @Test
    void aTagCannotPublishAnUnsignedOrUnnotarizedDmg() throws IOException {
        String release =
                Files.readString(Path.of(".github", "workflows", "release.yml")).replace("\r\n", "\n");
        int importCert = release.indexOf("- name: Import signing certificate (macOS)");
        int build = release.indexOf("- name: Build the installer");
        int notarize = release.indexOf("- name: Notarize the dmg (macOS)");
        int stage = release.indexOf("- name: Stage");
        assertTrue(importCert >= 0 && notarize >= 0, "the macOS legs no longer sign and notarize");
        assertTrue(
                importCert < build && build < notarize && notarize < stage,
                "the keychain exists before jpackage signs, and the dmg is notarized before it is staged");
        String importStep = release.substring(importCert, build);
        assertTrue(
                importStep.contains("GITHUB_REF_TYPE\" = \"tag\"") && importStep.contains("exit 1"),
                "a tag without the signing secrets must fail");
        assertTrue(importStep.contains("--mac-sign") && importStep.contains("--mac-entitlements"));
        assertTrue(release.substring(build, notarize).contains("-Djpackage.signArgs="), "the build passes the options");
        String notarizeStep = release.substring(notarize, stage);
        assertTrue(notarizeStep.contains("stapler staple") && notarizeStep.contains("spctl --assess"));
        assertTrue(
                release.contains("if: always() && steps.macsign.outputs.enabled == 'true'")
                        && release.contains("security delete-keychain"),
                "the keychain holding the private key is removed even when the leg fails");
    }
}
