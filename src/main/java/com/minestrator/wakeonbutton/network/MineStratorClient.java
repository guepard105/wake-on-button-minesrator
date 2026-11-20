package com.minestrator.wakeonbutton.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Client HTTP minimal pour l'API MineStrator.
 *
 * <p>Le site n'expose pas d'API publique documentee : les points d'entree
 * ci-dessous sont ceux utilises par le panel web (MineBoard). Le flux complet
 * tient en trois appels :</p>
 * <ol>
 *   <li>{@code POST /user/login} : connexion, renvoie une cle API ({@code api_authkey}) ;</li>
 *   <li>{@code PUT /server/{id}/poweraction} : demande de demarrage ;</li>
 *   <li>{@code GET /server/{id}/live} : etat du serveur (offline, starting, running...).</li>
 * </ol>
 *
 * <p>La cle API est envoyee en en-tete {@code Authorization: Bearer base64(cle)}
 * et reste valide environ 90 jours : elle peut etre mise en cache pour eviter
 * une connexion complete a chaque clic.</p>
 *
 * <p>La classe est sans etat : le client HTTP et l'instance Gson sont partages
 * et immuables. Une meme instance peut donc etre utilisee depuis plusieurs
 * threads.</p>
 *
 * <p>Note : Gson est importe via {@code com.google.gson} puis deplace vers
 * {@code com.minestrator.wakeonbutton.shaded.gson} par shadowJar dans le jar
 * final. Ecrire directement le nom shade dans les sources ne compilerait pas,
 * la relocalisation etant appliquee aux octets du jar apres compilation.</p>
 */
public final class MineStratorClient {

    /** Racine de l'API utilisee par le panel web. */
    public static final String DEFAULT_BASE_URL = "https://mine.sttr.io";

    /** Valeur envoyee au login quand aucun jeton Turnstile n'est disponible. */
    private static final String EMPTY_TURNSTILE = "";

    /** User-Agent navigateur : l'API refuse parfois les clients "Java". */
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Nombre de nouvelles tentatives apres le premier essai (delais 1s, 2s, 4s). */
    private static final int MAX_ATTEMPTS = 3;

    /** Client HTTP partage : immuable, donc thread-safe. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Instance Gson partagee : sans etat, reutilisable entre threads. */
    private static final Gson GSON = new Gson();

    /** Actions acceptees par l'endpoint poweraction. */
    public enum PowerAction {
        START("start"),
        STOP("stop"),
        RESTART("restart"),
        KILL("kill");

        private final String wireValue;

        PowerAction(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /** Etat observe d'un serveur, tel que renvoye par /server/{id}/live. */
    public record ServerStatus(String state) {

        /** Vrai si le serveur est deja en ligne (rien a demarrer). */
        public boolean isRunning() {
            return "running".equalsIgnoreCase(state) || "online".equalsIgnoreCase(state);
        }

        /** Vrai si le serveur est hors ligne (demarrage possible). */
        public boolean isOffline() {
            return state == null || state.isBlank() || "offline".equalsIgnoreCase(state);
        }
    }

    /** Erreur applicative renvoyee par l'API ou erreur reseau definitve. */
    public static final class MineStratorException extends Exception {

        private final int httpStatus;
        private final String errorCode;

        public MineStratorException(int httpStatus, String errorCode, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }

        public MineStratorException(String message, Throwable cause) {
            super(message, cause);
            this.httpStatus = 0;
            this.errorCode = null;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String errorCode() {
            return errorCode;
        }

        /** Vrai si la cle API est expiree ou invalide (relogin necessaire). */
        public boolean isUnauthorized() {
            return httpStatus == 401 || httpStatus == 403
                    || "API_USER_NOT_LOGGED".equals(errorCode)
                    || "API_USER_LOGIN_REQUIRED".equals(errorCode);
        }
    }

    private final String baseUrl;

    public MineStratorClient() {
        this(DEFAULT_BASE_URL);
    }

    public MineStratorClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ------------------------------------------------------------------
    // API publique
    // ------------------------------------------------------------------

    /**
     * Se connecte et renvoie la cle API MineStrator.
     *
     * @throws MineStratorException si l'API refuse la connexion (mauvais
     *         identifiants, compte suspendu, double authentification...)
     */
    public String login(String username, String password) throws MineStratorException {
        requireText(username, "username");
        requireText(password, "password");

        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        payload.addProperty("turnstile_token", EMPTY_TURNSTILE);

        HttpRequest request = newRequest("/user/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        JsonObject api = execute(request, "connexion MineStrator");
        JsonObject data = asObject(api.get("data"));
        if (data != null && data.has("tfa") && !data.get("tfa").isJsonNull()
                && data.get("tfa").getAsInt() == 1) {
            throw new MineStratorException(200, "API_USER_TFA_REQUIRED",
                    "double authentification activee : connexion par API impossible");
        }
        JsonObject user = data == null ? null : asObject(data.get("user"));
        String apiKey = user == null ? null : asString(user.get("api_authkey"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new MineStratorException(200, "API_USER_LOGIN_NO_KEY",
                    "reponse de connexion sans cle API");
        }
        return apiKey;
    }

    /**
     * Envoie une action de puissance (demarrage, arret, redemarrage, arret force).
     *
     * @throws MineStratorException si l'API refuse l'action
     */
    public void powerAction(String apiKey, String serverId, PowerAction action) throws MineStratorException {
        requireText(apiKey, "apiKey");
        requireText(serverId, "serverId");

        JsonObject payload = new JsonObject();
        payload.addProperty("poweraction", action.wireValue());

        HttpRequest request = newRequest("/server/" + encodePath(serverId) + "/poweraction")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + encodeKey(apiKey))
                .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        execute(request, "action " + action.wireValue());
    }

    /**
     * Lit l'etat courant du serveur.
     *
     * @throws MineStratorException si l'API refuse la requete
     */
    public ServerStatus fetchStatus(String apiKey, String serverId) throws MineStratorException {
        requireText(apiKey, "apiKey");
        requireText(serverId, "serverId");

        HttpRequest request = newRequest("/server/" + encodePath(serverId) + "/live")
                .header("Authorization", "Bearer " + encodeKey(apiKey))
                .GET()
                .build();

        JsonObject api = execute(request, "etat du serveur");
        JsonObject data = asObject(api.get("data"));
        String state = data == null ? null : asString(data.get("state"));
        return new ServerStatus(state);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** Construit une requete de base avec les en-tetes communs. */
    private HttpRequest.Builder newRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://minestrator.com")
                .header("Referer", "https://minestrator.com/");
    }

    /**
     * Envoie la requete avec jusqu'a trois tentatives sur erreur reseau, 5xx
     * ou 429, puis verifie l'enveloppe {@code {"api": {...}}} et son code.
     *
     * @return l'objet {@code api} de la reponse
     */
    private JsonObject execute(HttpRequest request, String operation) throws MineStratorException {
        MineStratorException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonObject api = parseEnvelope(response, operation);
                if (api != null) {
                    return api;
                }
                last = new MineStratorException(response.statusCode(), "HTTP_" + response.statusCode(),
                        operation + " : reponse vide");
            } catch (IOException e) {
                last = new MineStratorException(operation + " : erreur reseau (" + e.getMessage() + ")", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MineStratorException(operation + " : interrompu", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                pause(1000L << (attempt - 1));
            }
        }
        throw last;
    }

    /**
     * Verifie le statut HTTP et le code applicatif.
     *
     * @return l'objet {@code api}, ou {@code null} si la reponse doit etre retentee
     */
    private JsonObject parseEnvelope(HttpResponse<String> response, String operation)
            throws MineStratorException {
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            // Erreur temporaire : on laisse la boucle reessayer.
            return null;
        }
        JsonObject api = null;
        try {
            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
            if (root != null && root.has("api") && root.get("api").isJsonObject()) {
                api = root.getAsJsonObject("api");
            }
        } catch (JsonParseException ignored) {
            // Reponse non JSON : traitee comme une erreur generique ci-dessous.
        }

        int code = status;
        String error = null;
        if (api != null && api.has("code") && !api.get("code").isJsonNull()) {
            code = api.get("code").getAsInt();
        }
        if (api != null && api.has("error") && !api.get("error").isJsonNull()) {
            error = api.get("error").getAsString();
        }
        if (status >= 400 || code != 200) {
            String message = error != null ? error : ("HTTP " + status);
            throw new MineStratorException(status, error, operation + " : " + message);
        }
        if (api == null) {
            throw new MineStratorException(status, "API_EMPTY_RESPONSE",
                    operation + " : enveloppe api absente");
        }
        return api;
    }

    /** Encodage Base64 attendu par l'API pour l'en-tete Authorization. */
    private static String encodeKey(String apiKey) {
        return Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Echappe sommairement un identifiant de serveur dans un chemin d'URL. */
    private static String encodePath(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", (int) c));
            }
        }
        return sb.toString();
    }

    private static JsonObject asObject(com.google.gson.JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String asString(com.google.gson.JsonElement element) {
        return element != null && !element.isJsonNull() ? element.getAsString() : null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " vide");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
