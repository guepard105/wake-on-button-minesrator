package com.minestrator.wakeonbutton;

import com.minestrator.wakeonbutton.client.GuiHandler;
import com.minestrator.wakeonbutton.config.ConfigManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Point d'entree du mod WakeOnButton pour Neoforge 1.21.1.
 *
 * Le mod est purement cote client (side="CLIENT" dans neoforge.mods.toml).
 * Il initialise le gestionnaire de configuration et enregistre le handler
 * d'injection GUI pour ajouter le bouton "Reveiller le serveur" dans l'ecran
 * pause (in-game) et/ou l'ecran titre selon la configuration.
 */
@Mod(WakeOnButtonMod.MODID)
public class WakeOnButtonMod {

    /** Identifiant unique du mod (doit correspondre a neoforge.mods.toml). */
    public static final String MODID = "wakeonbutton";

    /**
     * Constructeur appele par NeoForge au chargement du mod.
     *
     * @param modEventBus bus d'evenements du mod (pour enregistrements)
     * @param modContainer conteneur du mod (metadonnees)
     */
    public WakeOnButtonMod(IEventBus modEventBus, ModContainer modContainer) {
        // Charge la configuration des le demarrage (cote client uniquement)
        if (FMLEnvironment.dist.isClient()) {
            ConfigManager.getInstance().load();

            // Enregistre le handler GUI sur le bus NeoForge (cote client)
            NeoForge.EVENT_BUS.register(GuiHandler.class);
        }
    }
}