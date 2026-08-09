/**
 * Modula — a simple FM/AM broadcast radio receiver for RTL-SDR dongles.
 *
 * <p>Layering rule: {@code dsp}, {@code demod} and {@code band} are pure — no JavaFX, no IO, no
 * threads, and no allocation after construction. {@code source} and {@code audio} are the only
 * packages that touch sockets, files or the sound card. {@code radio} is the only package that owns
 * threads. {@code ui} is the only package that touches JavaFX.
 */
module com.modula {
    requires javafx.controls;
    requires java.desktop; // javax.sound.sampled
    requires java.logging; // config failures are logged, never thrown

    exports com.modula;
}
