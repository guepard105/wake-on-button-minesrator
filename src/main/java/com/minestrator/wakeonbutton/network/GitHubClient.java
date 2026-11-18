package com.minestrator.wakeonbutton.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Client HTTP minimal pour l'API GitHub Actions.
 *
 * Il sert a declencher le workflow qui produit le paquet de reveil, puis a
 * attendre l'artifact correspondant et a en extraire le fichier packet.b64.
 *
 * La classe est sans etat : le client HTTP et l'instance Gson sont partages et
 * immuables, aucune donnee mutable n'est conservee entre les appels. Une meme
 * instance peut donc etre utilisee depuis plusieurs threads.
 *
 * Note : Gson est importe via com.google.gson puis deplace vers
 * com.minestrator.wakeonbutton.shaded.gson par shadowJar dans le jar final.
 * Ecrire directement le nom shade dans les sources ne compilerait pas, la
 * relocalisation etant appliquee aux octets du jar apres compilation.
 */
public final class GitHubClient {

    private static final String BASE_URL = "https://api.github.com";
    private static final String ACCEPT = "application/vnd.github+json";

    /** Nom du fichier recherche dans l'artifact (contenu base64). */
    private static final String PACKET_FILE = "packet.b64";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Timeout global d'une requete de dispatch. */
    private static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(30);
    /** Timeout d'une requete de polling / telechargement d'artifact. */
    private static final Duration ARTIFACT_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

    /** Nombre de nouvelles tentatives apres le premier essai (delais 1s, 2s, 4s). */
    private static final int MAX_RETRIES = 3;
    /** Plafond de securite pour l'en-tete Retry-After (en secondes). */
    private static final long MAX_RETRY_AFTER_SECONDS = 60L;

    /** Client HTTP partage : immuable, donc thread-safe. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** Instance Gson partagee : sans etat, reutilisable entre threads. */
    private static final Gson GSON = new Gson();

    public GitHubClient() {
        // Aucun etat : l'instance peut etre partagee entre plusieurs threads.
    }

    /**
     * Declenche un workflow via workflow_dispatch et retourne l'identifiant du run cree.
     *
     * Le traitement (envoi HTTP, retries, recherche du run) s'execute dans un
     * thread dedie (daemon) : le future est complete avec le runId sous forme
     * de chaine, ou echoue avec une {@link GitHubException}.
     *
     * @param owner      proprietaire du depot
     * @param repo       nom du depot
     * @param workflowId identifiant ou nom de fichier du workflow
     * @param inputs     inputs du workflow (peut etre null)
     * @param token      jeton GitHub (Bearer)
     * @return future contenant l'identifiant du run declenche
     */
    public CompletableFuture<String> dispatchWorkflow(String owner, String repo, String workflowId,
                                                      Map<String, String> inputs, String token) {
        requireText(owner, "owner");
        requireText(repo, "repo");
        requireText(workflowId, "workflowId");
        requireText(token, "token");

        // Corps JSON : {"ref": "main", "inputs": {...}}. Il est construit avant
        // l'appel asynchrone pour ne dependre d'aucun etat mutable partage.
        JsonObject payload = new JsonObject();
        payload.addProperty("ref", "main");
        if (inputs != null && !inputs.isEmpty()) {
            JsonObject inputsJson = new JsonObject();
            for (Map.Entry<String, String> entry : inputs.entrySet()) {
                inputsJson.addProperty(entry.getKey(), entry.getValue());
            }
            payload.add("inputs", inputsJson);
        }
        String body = GSON.toJson(payload);

        return async(() -> dispatchWorkflowBlocking(owner, repo, workflowId, body, token));
    }

    /**
     * Attend l'apparition d'un artifact et retourne le contenu decode de packet.b64.
     *
     * Le polling est effectue dans un thread dedie (daemon) afin de ne pas bloquer
     * l'appelant ; le resultat est expose via un CompletableFuture.
     *
     * @param owner        proprietaire du depot
     * @param repo         nom du depot
     * @param runId        identifiant du run (numerique, sous forme de chaine)
     * @param artifactName nom de l'artifact recherche (ex. "wake-packet")
     * @param token        jeton GitHub (Bearer)
     * @param maxWait      duree maximale d'attente (60s par defaut si null)
     * @param pollInterval intervalle de polling (2s par defaut si null)
     * @return future contenant le paquet de reveil decode
     */
    public CompletableFuture<byte[]> waitForArtifact(String owner, String repo, String runId,
                                                     String artifactName, String token,
                                                     Duration maxWait, Duration pollInterval) {
        requireText(owner, "owner");
        requireText(repo, "repo");
        requireText(artifactName, "artifactName");
        requireText(token, "token");
        long numericRunId = parseRunId(runId);
        Duration effectiveMaxWait = maxWait != null ? maxWait : DEFAULT_MAX_WAIT;
        Duration effectivePoll = pollInterval != null ? pollInterval : DEFAULT_POLL_INTERVAL;
        if (effectivePoll.isZero() || effectivePoll.isNegative()) {
            throw new IllegalArgumentException("pollInterval doit etre strictement positif.");
        }

        String cleanRunId = runId.trim();
        return async(() -> waitForArtifactBlocking(owner, repo, cleanRunId, numericRunId,
                artifactName, token, effectiveMaxWait, effectivePoll));
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private String dispatchWorkflowBlocking(String owner, String repo, String workflowId,
                                            String body, String token) {
        URI uri = URI.create(BASE_URL + "/repos/" + owner + "/" + repo
                + "/actions/workflows/" + workflowId + "/dispatches");
        HttpRequest request = newRequest(uri, token)
                .timeout(DISPATCH_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        String operation = "dispatch du workflow " + workflowId;
        long dispatchedAtMillis = System.currentTimeMillis();
        HttpResponse<String> response = executeWithRetry(request, HttpResponse.BodyHandlers.ofString(), operation);
        ensureSuccess(response, operation);

        // GitHub renvoie normalement 204 sans corps ; on tente d'abord Location,
        // puis on retombe sur la liste des runs récents.
        Long runId = extractRunIdFromLocation(response);
        if (runId == null) {
            runId = findLatestRunId(owner, repo, token, dispatchedAtMillis);
        }
        return String.valueOf(runId);
    }

    /**
     * Retrouve le run workflow_dispatch créé juste après notre requête.
     *
     * <p>GitHub répond 204 sans en-tête Location pour un dispatch : on liste donc
     * les runs récents (tous statuts confondus, un workflow rapide peut déjà être
     * terminé) et on retient le premier créé après le dispatch, avec une tolérance
     * de 10 secondes sur les horloges.</p>
     */
    private Long findLatestRunId(String owner, String repo, String token, long dispatchedAtMillis) {
        URI uri = URI.create(BASE_URL + "/repos/" + owner + "/" + repo
                + "/actions/runs?event=workflow_dispatch&per_page=10");
        long deadline = System.nanoTime() + DISPATCH_TIMEOUT.toNanos();
        long notBeforeMillis = dispatchedAtMillis - 10_000L;

        while (System.nanoTime() < deadline) {
            HttpRequest request = newRequest(uri, token)
                    .timeout(DISPATCH_TIMEOUT)
                    .GET()
                    .build();
            String operation = "recherche du run déclenché";
            HttpResponse<String> response = executeWithRetry(request, HttpResponse.BodyHandlers.ofString(), operation);
            ensureSuccess(response, operation);

            RunsPage page = parse(response.body(), RunsPage.class, operation);
            if (page.workflow_runs != null) {
                for (DispatchResponse run : page.workflow_runs) {
                    if (run.run_id() > 0 && isRecent(run.created_at(), notBeforeMillis)) {
                        return run.run_id();
                    }
                }
            }
            // Le dispatch est asynchrone : on laisse à GitHub le temps de créer le run.
            pause(1000L);
        }
        String message = "Aucun run workflow_dispatch détecté après le dispatch.";
        logError(message);
        throw new GitHubException(404, message);
    }

    /**
     * Vrai si l'horodatage ISO-8601 du run est postérieur à la limite donnée.
     * Un horodatage absent ou illisible est accepté (comportement permissif).
     */
    private static boolean isRecent(String createdAt, long notBeforeMillis) {
        if (createdAt == null || createdAt.isBlank()) {
            return true;
        }
        try {
            long createdMillis = OffsetDateTime.parse(createdAt).toInstant().toEpochMilli();
            return createdMillis >= notBeforeMillis;
        } catch (DateTimeParseException e) {
            logError("Horodatage de run illisible : " + createdAt);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Polling de l'artifact
    // ------------------------------------------------------------------

    private byte[] waitForArtifactBlocking(String owner, String repo, String runId, long numericRunId,
                                           String artifactName, String token,
                                           Duration maxWait, Duration pollInterval) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            ArtifactResponse artifact = findArtifact(owner, repo, runId, artifactName, token);
            if (artifact != null) {
                byte[] zip = downloadArtifact(artifact.archive_download_url(), token);
                return extractPacket(zip, artifact.name());
            }
            if (System.nanoTime() >= deadline) {
                String message = "Artifact " + artifactName + " introuvable pour le run " + numericRunId
                        + " apres " + maxWait.toSeconds() + "s.";
                logError(message);
                throw new GitHubException(404, message);
            }
            pause(Math.max(1L, pollInterval.toMillis()));
        }
    }

    private ArtifactResponse findArtifact(String owner, String repo, String runId,
                                          String artifactName, String token) {
        URI uri = URI.create(BASE_URL + "/repos/" + owner + "/" + repo
                + "/actions/runs/" + runId + "/artifacts");
        HttpRequest request = newRequest(uri, token)
                .timeout(ARTIFACT_TIMEOUT)
                .GET()
                .build();

        String operation = "liste des artifacts du run " + runId;
        HttpResponse<String> response = executeWithRetry(request, HttpResponse.BodyHandlers.ofString(), operation);
        ensureSuccess(response, operation);

        ArtifactsPage page = parse(response.body(), ArtifactsPage.class, operation);
        if (page.artifacts == null) {
            return null;
        }
        for (ArtifactResponse artifact : page.artifacts) {
            if (artifactName.equals(artifact.name()) && !artifact.expired()) {
                return artifact;
            }
        }
        return null;
    }

    private byte[] downloadArtifact(String archiveUrl, String token) {
        if (archiveUrl == null || archiveUrl.isBlank()) {
            throw new GitHubException(404, "URL de telechargement de l'artifact absente.");
        }

        URI uri = URI.create(archiveUrl);
        HttpRequest request = newRequest(uri, token)
                .timeout(ARTIFACT_TIMEOUT)
                .GET()
                .build();
        String operation = "telechargement de l'artifact";
        HttpResponse<byte[]> response = executeWithRetry(request, HttpResponse.BodyHandlers.ofByteArray(), operation);

        // GitHub redirige vers une URL signee : on la suit sans renvoyer le jeton.
        if (response.statusCode() == 301 || response.statusCode() == 302
                || response.statusCode() == 303 || response.statusCode() == 307
                || response.statusCode() == 308) {
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                throw new GitHubException(response.statusCode(), "Redirection sans en-tete Location.");
            }
            HttpRequest signedRequest = HttpRequest.newBuilder(URI.create(location))
                    .timeout(ARTIFACT_TIMEOUT)
                    .header("Accept", ACCEPT)
                    .GET()
                    .build();
            response = executeWithRetry(signedRequest, HttpResponse.BodyHandlers.ofByteArray(),
                    operation + " (URL signee)");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "Echec du telechargement de l'artifact (HTTP "
                    + response.statusCode() + ").";
            logError(message);
            throw new GitHubException(response.statusCode(), message);
        }
        return response.body();
    }

    /**
     * Ecrit le ZIP dans un fichier temporaire, en extrait packet.b64 puis decode
     * son contenu base64. Le fichier temporaire est toujours supprime.
     */
    private byte[] extractPacket(byte[] zipBytes, String artifactLabel) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("wakeonbutton-artifact-", ".zip");
            Files.write(tempFile, zipBytes);

            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(tempFile))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    if (entryName.equals(PACKET_FILE) || entryName.endsWith("/" + PACKET_FILE)) {
                        ByteArrayOutputStream content = new ByteArrayOutputStream();
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            content.write(buffer, 0, read);
                        }
                        String encoded = content.toString(StandardCharsets.US_ASCII).replaceAll("\\s", "");
                        return Base64.getDecoder().decode(encoded);
                    }
                }
            }
            throw new GitHubException(404, "Fichier " + PACKET_FILE
                    + " absent de l'artifact " + artifactLabel + ".");
        } catch (IOException e) {
            throw new GitHubException(0, "Lecture de l'artifact " + artifactLabel
                    + " impossible : " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new GitHubException(0, "Contenu base64 invalide dans l'artifact "
                    + artifactLabel + ".", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    logError("Suppression du ZIP temporaire impossible : " + e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP : requetes, retries et gestion des statuts
    // ------------------------------------------------------------------

    private static HttpRequest.Builder newRequest(URI uri, String token) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT);
    }

    /**
     * Envoie la requete avec 3 nouvelles tentatives (1s, 2s, 4s) sur erreurs
     * serveur (5xx), erreurs reseau et rate limit (Retry-After).
     */
    private <T> HttpResponse<T> executeWithRetry(HttpRequest request,
                                                 HttpResponse.BodyHandler<T> handler,
                                                 String operation) {
        IOException lastNetworkError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<T> response = HTTP_CLIENT.send(request, handler);
                int status = response.statusCode();

                if (status >= 500) {
                    if (attempt == MAX_RETRIES) {
                        String message = "Erreur serveur GitHub (HTTP " + status
                                + ") lors de : " + operation;
                        logError(message);
                        throw new GitHubException(status, message);
                    }
                    pause(backoffSeconds(attempt) * 1000L);
                    continue;
                }

                if (isRateLimited(response) && attempt < MAX_RETRIES) {
                    long waitSeconds = retryAfterSeconds(response);
                    logError("Limite de requetes GitHub atteinte lors de : " + operation
                            + ". Pause de " + waitSeconds + "s.");
                    pause(waitSeconds * 1000L);
                    continue;
                }

                return response;
            } catch (IOException e) {
                lastNetworkError = e;
                if (attempt == MAX_RETRIES) {
                    break;
                }
                pause(backoffSeconds(attempt) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitHubException(0, "Traitement interrompu lors de : " + operation, e);
            }
        }
        String message = "Echec reseau lors de : " + operation
                + (lastNetworkError != null ? " (" + lastNetworkError.getMessage() + ")" : "");
        logError(message);
        throw new GitHubException(0, message, lastNetworkError);
    }

    /** Delai de backoff exponentiel : 1s, 2s, 4s. */
    private static long backoffSeconds(int attempt) {
        return 1L << attempt;
    }

    private static boolean isRateLimited(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status != 403 && status != 429) {
            return false;
        }
        boolean quotaEpuise = response.headers().firstValue("x-ratelimit-remaining")
                .map("0"::equals)
                .orElse(false);
        return quotaEpuise || response.headers().firstValue("retry-after").isPresent();
    }

    private static long retryAfterSeconds(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException e) {
                        return MAX_RETRY_AFTER_SECONDS;
                    }
                })
                .map(seconds -> Math.min(MAX_RETRY_AFTER_SECONDS, Math.max(1L, seconds)))
                .orElse(MAX_RETRY_AFTER_SECONDS);
    }

    /** Convertit un statut non 2xx en GitHubException explicite. */
    private static void ensureSuccess(HttpResponse<?> response, String operation) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }

        String detail = "";
        if (response.body() instanceof String body && !body.isBlank()) {
            detail = " : " + truncate(body);
        }

        String message = switch (status) {
            case 401 -> "Token GitHub invalide ou expire";
            case 403 -> isRateLimited(response)
                    ? "Limite de requetes GitHub atteinte"
                    : "Acces refuse par GitHub";
            case 404 -> "Ressource introuvable (depot, workflow, run ou artifact)";
            case 422 -> "Parametres invalides (inputs ou reference)";
            default -> "Erreur GitHub (HTTP " + status + ")";
        };
        String fullMessage = message + " lors de : " + operation + detail;
        logError(fullMessage);
        throw new GitHubException(status, fullMessage);
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    /** Execute une tache bloquante dans un thread daemon dedie. */
    private static <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, runnable -> {
            Thread thread = new Thread(runnable, "wakeonbutton-github-client");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static Long extractRunIdFromLocation(HttpResponse<?> response) {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
            return null;
        }
        int slash = location.lastIndexOf('/');
        if (slash < 0 || slash == location.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(location.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            logError("En-tete Location illisible : " + location);
            return null;
        }
    }

    private static long parseRunId(String runId) {
        requireText(runId, "runId");
        try {
            long value = Long.parseLong(runId.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("runId doit etre positif : " + runId);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("runId doit etre numerique : " + runId, e);
        }
    }

    private <T> T parse(String body, Class<T> type, String operation) {
        try {
            T value = GSON.fromJson(body, type);
            if (value == null) {
                throw new GitHubException(502, "Reponse JSON vide lors de : " + operation);
            }
            return value;
        } catch (JsonParseException e) {
            throw new GitHubException(502, "Reponse JSON illisible lors de : " + operation, e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Parametre obligatoire manquant : " + name);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(0, "Traitement interrompu.", e);
        }
    }

    private static String truncate(String text) {
        String singleLine = text.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300) + "...";
    }

    private static void logError(String message) {
        System.err.println("[wakeonbutton] GitHub : " + message);
    }

    // ------------------------------------------------------------------
    // Types de reponse et exception
    // ------------------------------------------------------------------

    /** Reponse de dispatch : identifiants du run cree (champs JSON en snake_case). */
    public record DispatchResponse(long run_id, String run_url, String created_at) {
    }

    /** Artifact publie par un run (champs JSON en snake_case). */
    public record ArtifactResponse(long id, String name, String archive_download_url, boolean expired) {
    }

    /** Page de reponse de la liste des runs. */
    private static final class RunsPage {
        private List<DispatchResponse> workflow_runs;
    }

    /** Page de reponse de la liste des artifacts. */
    private static final class ArtifactsPage {
        private List<ArtifactResponse> artifacts;
    }

    /** Exception dediee aux erreurs de l'API GitHub. */
    public static final class GitHubException extends RuntimeException {

        private final int statusCode;

        public GitHubException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public GitHubException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        /** Code HTTP renvoye par GitHub, ou 0 pour une erreur locale / reseau. */
        public int getStatusCode() {
            return statusCode;
        }
    }
}
