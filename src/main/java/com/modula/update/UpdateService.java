package com.modula.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.modula.AppInfo;

/**
 * Asks once, off the receive path, whether a newer release exists.
 *
 * <p><b>Sends nothing.</b> It is a plain HTTPS GET of a public releases endpoint — no identifiers, no
 * telemetry — and the About window says so, because a radio that phones home unannounced deserves
 * the suspicion it would get.
 *
 * <p>Every failure is silent by design. There is no state in which a listener wants a dialog because
 * a version check could not reach the network.
 */
public final class UpdateService {

    private static final Logger LOG = Logger.getLogger(UpdateService.class.getName());

    /** A release payload is a few kilobytes; anything far larger is not one. */
    private static final int MAX_BYTES = 1 << 20;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> Thread.ofPlatform().name("modula-update").daemon(true).unstarted(r));

    private final String endpoint;

    public UpdateService(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    /** Whether an endpoint is configured at all; without one the feature is inert rather than broken. */
    public boolean isConfigured() {
        return endpoint.startsWith("https://");
    }

    /**
     * Checks in the background.
     *
     * @param callback receives the newer release, or null — always on the calling framework's thread
     *     via {@code onResult}, never on the worker
     */
    public void check(Consumer<ReleaseInfo> callback) {
        if (!isConfigured()) {
            callback.accept(null);
            return;
        }
        worker.execute(() -> {
            ReleaseInfo found = null;
            try {
                found = fetch();
            } catch (Exception e) {
                LOG.log(Level.FINE, "update check failed", e);
            }
            ReleaseInfo result = found;
            javafx.application.Platform.runLater(() -> callback.accept(result));
        });
    }

    private ReleaseInfo fetch() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    // GitHub rejects a request with no User-Agent outright.
                    .header("User-Agent", AppInfo.NAME + "/" + AppInfo.VERSION)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > MAX_BYTES) {
                return null;
            }
            ReleaseInfo latest = UpdateCheck.parseLatest(response.body());
            return latest != null && UpdateCheck.isNewer(AppInfo.VERSION, latest.version()) ? latest : null;
        }
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
