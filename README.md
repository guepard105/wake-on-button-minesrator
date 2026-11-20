# Wake on Button — Minestrator

Mod NeoForge 1.21.1, côté client uniquement, version 2.0.0, auteur `guepard103`. Il ajoute un bouton « Allumer le serveur » dans le menu pause et/ou l'écran titre de Minecraft : au clic, le mod démarre le serveur hébergé chez MineStrator en appelant directement l'API interne du panel, depuis le PC du joueur. Tout tient dans le jar : aucun script, aucun Python, aucun navigateur, aucun binaire natif, aucun droit administrateur.

## Comment ça marche

1. Le joueur clique sur « Allumer le serveur ».
2. Un thread d'arrière-plan déchiffre les identifiants MineStrator (AES-256-GCM) et utilise la clé API en cache si elle existe.
3. Si nécessaire, `POST https://mine.sttr.io/user/login` avec `{username, password, turnstile_token:""}` renvoie une clé API (`api_authkey`, 32 caractères) valable environ 90 jours ; elle est chiffrée et mise en cache dans la config.
4. `GET https://mine.sttr.io/server/{server_id}/live` donne l'état : `offline`, `starting`, `online`, `stopping`.
5. Si le serveur est hors ligne : `PUT https://mine.sttr.io/server/{server_id}/poweraction` avec `{"poweraction":"start"}`.
6. Le mod affiche un toast immédiat, puis interroge l'état toutes les 4 s pendant 180 s max et affiche « Serveur en ligne ! » quand l'état passe à `online`. Le démarrage réel prend en général 1 à 4 minutes selon le modpack.
7. Sur HTTP 401/403, la clé API en cache est considérée comme expirée et une reconnexion est faite automatiquement.

Tout est asynchrone : le jeu n'est jamais figé, aucune fenêtre modale.

## Configuration

Le fichier `config/wakeonbutton.json` se trouve dans le répertoire de jeu :

```json
{
  "minestrator": {
    "server_id": "511952",
    "username_encrypted": "<base64>",
    "password_encrypted": "<base64>",
    "api_key_encrypted": "<base64>",
    "kdf_iterations": 100000
  },
  "ui": {
    "show_in_pause_menu": true,
    "show_in_title_screen": true
  }
}
```

- `username_encrypted` / `password_encrypted` / `api_key_encrypted` sont au format `Base64(sel16 | iv12 | chiffré+tag)`.
- `config/wakeonbutton.key` est généré automatiquement au premier usage (base64 de `sel16 | graine32`, droits `rw-------` sous POSIX). Sans ce fichier, les champs chiffrés sont indéchiffrables.
- **Bootstrap sans outil** : le fichier accepte aussi `"username": "..."` et `"password": "..."` en clair ; au premier appel le mod les chiffre avec la clé locale, réécrit le fichier **sans** les champs en clair et les efface de la mémoire. Le fichier produit ne contient jamais de mot de passe en clair.
- Si l'on distribue la config, il faut distribuer la paire config + `wakeonbutton.key` (ou utiliser le bootstrap en clair).

## Sécurité

- AES-256-GCM pour chaque valeur ; clé dérivée par PBKDF2-HMAC-SHA256 (100 000 itérations, sel aléatoire de 16 octets).
- Clé maître dérivée par PBKDF2-HMAC-SHA256 (100 000 itérations) depuis une graine aléatoire de 32 octets stockée dans `wakeonbutton.key`.
- Écriture atomique de la config (fichier temporaire + rename), permissions restreintes, verrou lecture/écriture.
- Aucun identifiant n'est journalisé ; le log ne contient que des étapes et des messages d'erreur génériques.
- Aucun secret n'est présent dans le jar.

## Contenu du dépôt

- `src/main/java/com/minestrator/wakeonbutton/`
  - `WakeOnButtonMod.java` — point d'entrée `@Mod`, charge la config, enregistre le handler GUI côté client.
  - `client/GuiHandler.java` — injection dans `PauseScreen` / `TitleScreen` ; flow asynchrone (login, état, poweraction, polling, toasts), journal `wakeonbutton.log`.
  - `client/WakeButton.java` — bouton, libellé « Démarrage... », anti double-clic 5 s.
  - `network/MineStratorClient.java` — `java.net.http.HttpClient` HTTP/2, login, `poweraction`, `live`, retries 1 s / 2 s / 4 s sur erreur réseau, 5xx et 429, enveloppe `{"api":{...}}`, exception `MineStratorException` avec détection 401/403.
  - `config/ConfigManager.java` — schéma JSON, chiffrement AES-256-GCM, clé locale, migration du bootstrap en clair, cache de la clé API.
- `src/main/resources/` — `META-INF/neoforge.mods.toml` (version 2.0.0, auteur `guepard103`) et traductions `fr_fr` / `en_us`.
- `build.gradle.kts` / `settings.gradle.kts` — Kotlin DSL, plugin `net.neoforged.moddev` 2.0.148, NeoForge 21.1.267, Java 21, Shadow `com.gradleup.shadow` 8.3.6 avec configuration `shade` dédiée, Gson relocalisé sous `com.minestrator.wakeonbutton.shaded.gson`.
- Dépôt public : `https://github.com/guepard105/wake-on-button-minesrator`.

## Build

Prérequis : JDK 21. Le wrapper Gradle 8.10.2 est inclus (`./gradlew`).

```bash
./gradlew shadowJar
# → build/libs/wakeupbutton-2.0.0.jar (environ 330 Ko)
```

Le jar contient `META-INF/neoforge.mods.toml`, les classes du mod et Gson relocalisé ; il ne contient aucune classe `net/neoforged` ni `net/minecraft`.

Installation : déposer le jar dans le dossier `mods/` d'une instance NeoForge 1.21.1.

## Comportement et robustesse

- 100 % asynchrone, aucun freeze, aucune modale.
- Toasts : « Démarrage du serveur lancé... », « Serveur en ligne ! », « Le serveur est déjà en ligne. », messages d'erreur explicites.
- Anti double-clic 5 s ; bouton désactivé et libellé « Démarrage... » pendant la requête.
- Journal détaillé dans `wakeonbutton.log` à la racine du répertoire de jeu.
- Aucun droit administrateur, aucun script externe ; fonctionne sous Linux (X11/Wayland), Windows et macOS.

## Limites connues

- L'API utilisée est celle du panel web MineStrator, non documentée publiquement : elle peut changer sans préavis et casser le mod.
- La double authentification (2FA) du compte MineStrator empêche la connexion par API ; le mod affiche alors une erreur explicite.
- Le démarrage réel du serveur prend 1 à 4 minutes ; le mod arrête d'attendre après 180 s mais le serveur continue de démarrer.
- Le compte utilisé doit avoir accès au serveur indiqué par `server_id`.
