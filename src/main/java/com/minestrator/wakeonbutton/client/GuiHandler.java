package com.minestrator.wakeonbutton.client;

import com.minestrator.wakeonbutton.config.ConfigManager;
import com.minestrator.wakeonbutton.network.GitHubClient;
import com.minestrator.wakeonbutton.wol.WakePacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Intégration du bouton « Allumer le serveur » dans les écrans vanilla.
 *
 * <p>Deux points d'injection sont gérés :</p>
 * <ul>
 *     <li>{@link PauseScreen} (menu pause en jeu) : bouton placé sous « Options... » ;</li>
 *     <li>{@link TitleScreen} (écran titre) : bouton placé à droite de « Multijoueur ».</li>
 * </ul>
 *
 * <p>Chaque affichage est conditionné par la configuration
 * ({@code ui.show_in_pause_menu} / {@code ui.show_in_title_screen}).</p>
 *
 * <p>Le flow de réveil (dispatch GitHub Actions, attente de l'artifact, envoi du
 * magic packet) s'exécute entièrement en arrière-plan : le thread du jeu n'est
 * jamais bloqué et aucune modal n'est ouverte.</p>
 */
public final class GuiHandler {

    /** Journalisation : log du jeu + fichier wakeonbutton.log dans GAMEDIR. */
    private static final Logger LOGGER = LogManager.getLogger("wakeonbutton");

    /** Fichier de diagnostic, à la racine du répertoire de jeu. */
    private static final Path LOG_FILE = FMLPaths.GAMEDIR.get().resolve("wakeonbutton.log");

    /** Client GitHub partagé : sans état, thread-safe. */
    private static final GitHubClient GITHUB = new GitHubClient();

    /** Nom de l'artifact publié par le workflow. */
    private static final String ARTIFACT_NAME = "wake-packet";
    /** Attente maximale de l'artifact (le workflow doit se terminer). */
    private static final Duration ARTIFACT_MAX_WAIT = Duration.ofSeconds(180);
    /** Intervalle de polling de l'artifact. */
    private static final Duration ARTIFACT_POLL_INTERVAL = Duration.ofSeconds(2);

    /** Marge entre deux éléments vanilla. */
    private static final int PADDING = 4;
    /** Hauteur standard d'un bouton vanilla. */
    private static final int BUTTON_HEIGHT = 20;
    /** Largeur d'un demi-bouton vanilla (colonnes de l'écran titre). */
    private static final int HALF_BUTTON_WIDTH = 98;
    /** Largeur d'un bouton pleine largeur (grille du menu pause). */
    private static final int FULL_BUTTON_WIDTH = 204;

    private GuiHandler() {
        // Classe de handlers : pas d'instance.
    }

    // ------------------------------------------------------------------
    // Injection du bouton
    // ------------------------------------------------------------------

    /**
     * Injecte le bouton après l'initialisation complète de l'écran.
     *
     * @param event événement NeoForge émis une fois les widgets vanilla créés
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        ConfigManager config = ConfigManager.getInstance();

        // init() peut être rejoué (redimensionnement) : ne jamais empiler les boutons.
        if (hasWakeButton(screen)) {
            return;
        }

        if (screen instanceof PauseScreen) {
            if (!config.isShowInPauseMenu()) {
                return;
            }
            addToPauseScreen(screen, event);
        } else if (screen instanceof TitleScreen) {
            if (!config.isShowInTitleScreen()) {
                return;
            }
            addToTitleScreen(screen, event);
        }
    }

    /** Menu pause : bouton sous « Options... », décalé si la place est occupée. */
    private static void addToPauseScreen(Screen screen, ScreenEvent.Init.Post event) {
        AbstractWidget options = findButton(screen, "menu.options");
        if (options == null) {
            // Repli : la grille vanilla n'est pas identifiable, on se place au centre.
            event.addListener(WakeButton.create(screen.width / 2 - FULL_BUTTON_WIDTH / 2,
                    screen.height / 2 + 40, FULL_BUTTON_WIDTH));
            return;
        }
        int y = freeY(screen, options.getX(), options.getY() + options.getHeight() + PADDING,
                options.getWidth());
        event.addListener(WakeButton.create(options.getX(), y, options.getWidth()));
    }

    /** Écran titre : bouton à droite de « Multijoueur ». */
    private static void addToTitleScreen(Screen screen, ScreenEvent.Init.Post event) {
        AbstractWidget multiplayer = findButton(screen, "menu.multiplayer");
        int width;
        int x;
        int y;
        if (multiplayer != null) {
            width = multiplayer.getWidth();
            // Clamp : un écran étroit ne doit pas pousser le bouton hors de la fenêtre.
            x = Math.min(multiplayer.getX() + width + PADDING, screen.width - width - PADDING);
            y = multiplayer.getY();
        } else {
            // Repli : position usuelle de « Multijoueur » dans la grille 1.21.
            width = HALF_BUTTON_WIDTH;
            x = Math.min(screen.width / 2 + 106, screen.width - width - PADDING);
            y = screen.height / 4 + 48;
        }
        event.addListener(WakeButton.create(x, y, width));
    }

    /** Vrai si l'écran contient déjà notre bouton. */
    private static boolean hasWakeButton(Screen screen) {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof WakeButton) {
                return true;
            }
        }
        return false;
    }

    /** Retrouve un bouton vanilla par la valeur traduite de son libellé. */
    private static AbstractWidget findButton(Screen screen, String translationKey) {
        String wanted = Component.translatable(translationKey).getString();
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof AbstractWidget widget
                    && widget.getMessage().getString().equals(wanted)) {
                return widget;
            }
        }
        return null;
    }

    /**
     * Décale le bouton vers le bas tant qu'il chevauche un widget existant.
     * (« sous Options » peut tomber sur le bouton pleine largeur de la rangée suivante.)
     */
    private static int freeY(Screen screen, int x, int y, int width) {
        int candidate = y;
        for (int i = 0; i < 8; i++) {
            AbstractWidget colliding = firstCollision(screen, x, candidate, width, BUTTON_HEIGHT);
            if (colliding == null) {
                return candidate;
            }
            candidate = colliding.getY() + colliding.getHeight() + PADDING;
        }
        return candidate;
    }

    /** Premier widget visible dont la zone recoupe le rectangle demandé, ou null. */
    private static AbstractWidget firstCollision(Screen screen, int x, int y, int width, int height) {
        for (GuiEventListener listener : screen.children()) {
            if (!(listener instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            boolean horizontal = x < widget.getX() + widget.getWidth() && widget.getX() < x + width;
            boolean vertical = y < widget.getY() + widget.getHeight() && widget.getY() < y + height;
            if (horizontal && vertical) {
                return widget;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Flow de réveil (entièrement asynchrone)
    // ------------------------------------------------------------------

    /**
     * Lance le flow de réveil sans jamais bloquer le thread client.
     *
     * <p>Enchaînement : configuration → jeton → dispatch du workflow → attente de
     * l'artifact → envoi du magic packet → notification.</p>
     *
     * @param button bouton à remettre en état normal à la fin du flow
     */
    static void startWakeFlow(WakeButton button) {
        ConfigManager config = ConfigManager.getInstance();
        String mac = config.getTargetMac();
        String owner = config.getGithubOwner();
        String repo = config.getGithubRepo();
        String workflowId = config.getGithubWorkflowId();

        // 1. Configuration minimale présente ?
        if (isBlank(mac) || isBlank(owner) || isBlank(repo) || isBlank(workflowId)) {
            log("configuration incomplète : MAC, dépôt ou workflow manquant");
            toastError(Component.translatable("wakeonbutton.toast.config_missing"));
            return;
        }

        // 2. Bouton désactivé et libellé « En cours... » jusqu'à la fin du flow.
        button.setWaking(true);
        log("réveil demandé (mac=" + mac + ", dépôt=" + owner + "/" + repo
                + ", workflow=" + workflowId + ")");

        // 3 à 8. Jeton (hors thread client), dispatch, artifact puis magic packet.
        fetchToken(config)
                .thenCompose(token -> GITHUB
                        .dispatchWorkflow(owner, repo, workflowId,
                                buildInputs(mac, config.getPasswordHex()), token)
                        .thenCompose(runId -> {
                            log("workflow dispatché (run " + runId + ") : attente de l'artifact");
                            return GITHUB.waitForArtifact(owner, repo, runId, ARTIFACT_NAME, token,
                                    ARTIFACT_MAX_WAIT, ARTIFACT_POLL_INTERVAL);
                        }))
                .thenCompose(packet -> {
                    log("artifact reçu (" + packet.length + " octets) : envoi du magic packet");
                    return WakePacketSender.sendMagicPacket(packet);
                })
                // 9 et 10. Retour sur le thread client : notification puis réactivation.
                .whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
                    button.setWaking(false);
                    if (error == null) {
                        log("magic packet envoyé : serveur réveillé");
                        toastSuccess();
                    } else {
                        reportError(error);
                    }
                }));
    }

    /**
     * Déchiffre le jeton GitHub hors du thread client (PBKDF2 + AES-GCM sont
     * coûteux et doivent rester en arrière-plan).
     */
    private static CompletableFuture<String> fetchToken(ConfigManager config) {
        return CompletableFuture.supplyAsync(() -> {
            // Premier lancement : la clé maître est créée puis renvoyée.
            char[] masterKey = config.loadOrCreateMasterKey();
            if (masterKey == null) {
                throw new WakeFlowException("wakeonbutton.toast.first_run_setup");
            }
            try {
                String token = config.getGitHubToken(masterKey);
                if (isBlank(token)) {
                    throw new WakeFlowException("wakeonbutton.toast.token_decrypt_failed");
                }
                return token;
            } finally {
                // La clé ne doit pas rester en mémoire plus longtemps que nécessaire.
                Arrays.fill(masterKey, '\0');
            }
        });
    }

    /** Inputs du workflow : MAC obligatoire, mot de passe SecureOn optionnel. */
    private static Map<String, String> buildInputs(String mac, String passwordHex) {
        Map<String, String> inputs = new LinkedHashMap<>(2);
        inputs.put("target_mac", mac);
        if (!isBlank(passwordHex)) {
            inputs.put("password", passwordHex);
        }
        return inputs;
    }

    // ------------------------------------------------------------------
    // Notifications et diagnostic
    // ------------------------------------------------------------------

    /** Toast de succès. */
    private static void toastSuccess() {
        SystemToast.add(Minecraft.getInstance().getToasts(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("wakeonbutton.toast.success_title"),
                Component.translatable("wakeonbutton.toast.success"));
    }

    /** Toast d'erreur (message déjà localisé). */
    private static void toastError(Component message) {
        SystemToast.add(Minecraft.getInstance().getToasts(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("wakeonbutton.toast.error_title"),
                message);
    }

    /** Journalise puis notifie l'erreur (toujours sur le thread client). */
    private static void reportError(Throwable error) {
        Throwable cause = unwrap(error);
        String detail = describe(cause);
        log("échec du réveil : " + detail);
        if (cause instanceof WakeFlowException flowError) {
            // Erreur de configuration connue : message dédié.
            toastError(Component.translatable(flowError.translationKey()));
            return;
        }
        toastError(Component.translatable("wakeonbutton.toast.error", detail));
    }

    /** Déballe l'emballage des futures (CompletionException / ExecutionException). */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Résumé lisible d'une exception (jamais null). */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.getClass().getSimpleName() : message;
    }

    /** Vrai si la chaîne est absente ou blanche. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Journalise à la fois dans le log du jeu et dans {@code wakeonbutton.log}
     * (GAMEDIR). Synchronisé : le flow peut journaliser depuis plusieurs threads.
     */
    private static synchronized void log(String message) {
        LOGGER.info(message);
        String line = "[" + Instant.now() + "] " + message + System.lineSeparator();
        try {
            Path parent = LOG_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Le fichier est un confort de diagnostic : ne jamais casser le flow pour ça.
            LOGGER.warn("écriture dans {} impossible : {}", LOG_FILE, e.getMessage());
        }
    }

    /** Erreur du flow portant directement la clé de traduction à afficher. */
    private static final class WakeFlowException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String translationKey;

        WakeFlowException(String translationKey) {
            super(translationKey);
            this.translationKey = translationKey;
        }

        String translationKey() {
            return this.translationKey;
        }
    }
}
