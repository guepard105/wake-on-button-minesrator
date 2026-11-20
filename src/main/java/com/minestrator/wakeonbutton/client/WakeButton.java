package com.minestrator.wakeonbutton.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Bouton « Allumer le serveur » injecté dans les écrans vanilla.
 *
 * <p>Le bouton ne fait que déclencher le flow asynchrone de {@link GuiHandler} :
 * aucun traitement réseau ou cryptographique n'a lieu ici. Pendant l'exécution,
 * son libellé passe à « Démarrage... » et il est désactivé ; il revient à son
 * état normal une fois la demande envoyée (succès ou erreur).</p>
 */
public class WakeButton extends Button {

    /** Anti double-clic : 5 s minimum entre deux lancements. */
    private static final long COOLDOWN_MS = 5_000L;

    /** Hauteur standard d'un bouton vanilla. */
    private static final int HEIGHT = 20;

    /** Libellé au repos. */
    private static final Component LABEL_IDLE = Component.translatable("wakeonbutton.gui.start_server");
    /** Libellé pendant l'exécution du flow. */
    private static final Component LABEL_BUSY = Component.translatable("wakeonbutton.gui.waking");

    /** Vrai tant que le flow est en cours (lu depuis le thread client uniquement). */
    private volatile boolean waking;
    /** Horodatage du dernier clic accepté (thread client uniquement). */
    private long lastPressMs;

    /** Constructeur privé : passer par {@link #create(int, int, int)}. */
    private WakeButton(int x, int y, int width) {
        // Le OnPress du constructeur n'est jamais appelé : on redéfinit onPress().
        super(x, y, width, HEIGHT, LABEL_IDLE, button -> { }, DEFAULT_NARRATION);
    }

    /**
     * Fabrique un bouton prêt à être ajouté à un écran.
     *
     * @param x     position horizontale
     * @param y     position verticale
     * @param width largeur (alignée sur les boutons vanilla voisins)
     * @return le bouton créé
     */
    public static WakeButton create(int x, int y, int width) {
        return new WakeButton(x, y, width);
    }

    /**
     * Clic : applique l'anti double-clic puis délègue au flow asynchrone.
     * Le cooldown démarre dès le clic, même si la configuration est incomplète.
     */
    @Override
    public void onPress() {
        if (this.waking) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastPressMs < COOLDOWN_MS) {
            return;
        }
        this.lastPressMs = now;
        GuiHandler.startWakeFlow(this);
    }

    /**
     * Bascule l'état visuel du bouton.
     *
     * @param value {@code true} pendant l'exécution du flow, {@code false} au repos
     */
    void setWaking(boolean value) {
        this.waking = value;
        this.active = !value;
        this.setMessage(value ? LABEL_BUSY : LABEL_IDLE);
    }

    /** @return vrai si le flow est en cours pour ce bouton */
    boolean isWaking() {
        return this.waking;
    }
}
