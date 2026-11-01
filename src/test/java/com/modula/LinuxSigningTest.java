package com.modula;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Linux signing chain (README, "Signing"). It runs only in a release, and each mistake it
 * guards against ships quietly: a publish that does not wait for the signed files, a tag that releases
 * an unsigned deb, or a signature made by a key other than the one published.
 */
class LinuxSigningTest {

    private static String release() throws IOException {
        return Files.readString(Path.of(".github", "workflows", "release.yml")).replace("\r\n", "\n");
    }

    @Test
    void aTagCannotPublishAnUnsignedDeb() throws IOException {
        String release = release();
        int job = release.indexOf("\n  sign-linux:\n");
        int publish = release.indexOf("\n  publish:\n");
        assertTrue(job >= 0 && job < publish, "the release no longer signs the Linux package");
        String sign = release.substring(job, publish);
        assertTrue(
                sign.contains("GITHUB_REF_TYPE\" = \"tag\"") && sign.contains("exit 1"),
                "a tag without the signing key must fail");
        assertTrue(
                sign.contains("MODULA_GPG_EXPECTED_KEY=packaging/linux/signing-key.asc"),
                "a real key must be the committed one and no other");
        assertTrue(
                sign.indexOf("sign-linux-packages.sh") < sign.indexOf("check-linux-signatures.sh"),
                "what was signed is checked against the public key before it is uploaded");
        assertTrue(
                sign.contains("name: installer-linux-x64\n          path: linux/installer-linux-x64/*\n"
                        + "          overwrite: true"),
                "the signed files must replace the unsigned build artifact");
        assertTrue(
                release.substring(publish).contains("needs: [installers, sign-linux]"),
                "the publish job must wait for the signed files");
    }

    @Test
    void theCommittedKeyIsAPublicKey() throws IOException {
        String key = Files.readString(Path.of("packaging", "linux", "signing-key.asc"));
        assertTrue(key.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"), "signing-key.asc is not a public key");
        assertTrue(!key.contains("PRIVATE KEY"), "signing-key.asc must never hold the secret half");
    }

    @Test
    void theDetachedSignatureIsMadeAfterTheEmbeddedOne() throws IOException {
        String sign = Files.readString(Path.of("scripts", "sign-linux-packages.sh"));
        assertTrue(
                sign.indexOf("sign_deb \"$deb\"") < sign.indexOf("--armor --detach-sign"),
                "the embedded signature changes the file, so the detached one must come after");
        String check = Files.readString(Path.of("scripts", "check-linux-signatures.sh"));
        assertTrue(check.contains("gpgv --keyring"), "the check must use the public key alone, not a keyring");
    }
}
