# Wake on Button — Minestrator

Mod NeoForge 1.21.1 côté client qui ajoute un bouton « Réveiller le serveur » dans le menu pause et/ou l'écran titre de Minecraft. Au clic, il déclenche un workflow GitHub Actions qui fabrique un Magic Packet Wake-on-LAN, récupère le paquet produit puis l'envoie en UDP depuis l'ordinateur du joueur.

Le paquet part donc de la connexion du joueur et non d'un serveur : aucun script système, binaire natif ou droit administrateur n'est nécessaire. Tout le code client est contenu dans le jar du mod.

## Comment ça marche

1. Le joueur clique sur le bouton « Réveiller le serveur ».
2. Le mod déclenche un workflow GitHub Actions (`workflow_dispatch`) qui fabrique un Magic Packet Wake-on-LAN.
3. Il attend puis télécharge l'artifact `wake-packet`, qui contient `packet.b64` (le paquet encodé en base64).
4. Il envoie ce paquet en UDP (port 9, broadcast) depuis l'ordinateur du joueur.

## Contenu du dépôt

- `src/main/java/com/minestrator/wakeonbutton/`
  - `WakeOnButtonMod.java` — point d'entrée `@Mod` : charge la configuration et enregistre le handler GUI côté client.
  - `client/GuiHandler.java` — injection du bouton dans `PauseScreen` / `TitleScreen` et flow asynchrone complet.
  - `client/WakeButton.java` — le bouton, libellé « Réveil en cours… », anti double-clic de 5 s.
  - `network/GitHubClient.java` — `java.net.http.HttpClient`, `workflow_dispatch`, recherche du run, polling de l'artifact, extraction de `packet.b64`, retries (1 s / 2 s / 4 s sur 5xx et erreurs réseau), `GitHubException`.
  - `config/ConfigManager.java` — fichier `config/wakeonbutton.json`, jeton GitHub chiffré en AES-256-GCM, clé dérivée par PBKDF2-HMAC-SHA256 (100 000 itérations, sel aléatoire) ; clé maître locale dans `config/wakeonbutton.key` (droits `rw-------` sous POSIX) ; écriture atomique ; verrou lecture/écriture.
  - `wol/WakePacketSender.java` — envoi UDP avec 4 stratégies successives : broadcast limité 255.255.255.255, broadcast par interface, broadcast limité lié à chaque interface, unicast vers la passerelle supposée. Aucun droit administrateur.
- `wake-packet/` — sous-projet Gradle autonome (JDK pur, zéro dépendance) : `WakePacketGenerator` construit le Magic Packet (6 × 0xFF + 16 × MAC + mot de passe SecureOn optionnel de 4 ou 6 octets). CLI : `--mac=`, `--password=`, `--output-base64`, `--help`. Codes de sortie 0 à 4.
- `.github/workflows/build-wake-packet.yml` — `workflow_dispatch` (input `target_mac` requis, `password` optionnel), JDK 21, build `:wake-packet:shadowJar`, génère `packet.b64`, publie l'artifact `wake-packet` (rétention 1 jour).
- `build.gradle.kts` / `settings.gradle.kts` — Kotlin DSL, plugin `net.neoforged.moddev` 2.0.148, NeoForge 21.1.267, Java 21, Shadow (`com.gradleup.shadow` 8.3.6) avec une configuration `shade` dédiée (Gson + sous-projet) et relocation de Gson sous `com.minestrator.wakeonbutton.shaded.gson`.

## Build

Prérequis : JDK 21. Le wrapper Gradle 8.10.2 est inclus (`./gradlew`).

```bash
# Jar du mod
./gradlew shadowJar
# → build/libs/wakeonbutton-1.0.0.jar (environ 343 Ko)

# Générateur autonome de paquets
./gradlew :wake-packet:shadowJar
# → wake-packet/build/libs/wake-packet-1.0.0-all.jar
```

Vérifié : le jar final contient `META-INF/neoforge.mods.toml`, les classes du mod, le générateur et Gson relocalisé ; aucune classe `net/neoforged` ni `net/minecraft` n'est embarquée.

## Configuration

Le fichier `config/wakeonbutton.json` se trouve dans le répertoire de jeu :

```json
{
  "github": {
    "owner": "guepard105",
    "repo": "wake-on-button-minesrator",
    "workflow_id": "build-wake-packet.yml",
    "token_encrypted": "<base64(salt|iv|chiffré|tag)>",
    "token_kdf_iterations": 100000
  },
  "server": {
    "target_mac": "aa:bb:cc:dd:ee:ff",
    "password_hex": "11223344"
  },
  "ui": {
    "show_in_pause_menu": true,
    "show_in_title_screen": false
  }
}
```

- `password_hex` est optionnel (SecureOn, 4 ou 6 octets en hexadécimal).
- **Bootstrap sans outil** : le pack peut livrer un champ `"token": "ghp_..."` en clair dans la section `github`. Au premier appel, le mod chiffre ce jeton avec la clé locale, réécrit le fichier sans le clair et le supprime de la mémoire.
- `config/wakeonbutton.key` est généré automatiquement au premier lancement. Ce fichier ne doit jamais être publié avec un jeton chiffré qu'il permettrait de déchiffrer.
- Le jeton GitHub est chiffré au repos en AES-256-GCM ; la clé maître est dérivée par PBKDF2-HMAC-SHA256 (100 000 itérations).
- **Aucun identifiant de compte Minestrator n'est utilisé ni stocké par ce mod** : le flux ne transporte qu'un Magic Packet Wake-on-LAN.

## Prérequis GitHub

- Le dépôt doit contenir le workflow sur la branche par défaut (`main`).
- Le jeton (PAT classique avec les portées `repo` et `workflow`, ou jeton fine-grained avec `Actions: write` et `Contents: read` sur ce dépôt) doit pouvoir déclencher un `workflow_dispatch` et lire les artifacts.

## Comportement et robustesse

- Tout le flow est asynchrone : le jeu n'est jamais figé, aucune fenêtre modale.
- Toasts de succès/erreur ; journal détaillé dans `wakeonbutton.log` (répertoire de jeu) et dans le log du jeu.
- Anti double-clic de 5 s ; bouton désactivé avec le libellé « Réveil en cours… » pendant l'exécution.
- Attente maximale de l'artifact : 180 s (polling toutes les 2 s).
- Cross-platform Linux (X11/Wayland), Windows, macOS : aucun droit administrateur, aucun script externe.
- Débogage réseau : lancer le jeu avec `-Dwakeonbutton.debug=true` pour tracer chaque tentative d'envoi UDP.

## Limites connues

- Le réveil repose sur le Wake-on-LAN : la carte réseau du serveur doit être configurée pour accepter les magic packets.
- Le premier déclenchement après une longue inactivité peut être plus lent (démarrage à froid du runner GitHub Actions).
- Le mod n'ajoute aucun paquet réseau autre que le Magic Packet WoL.
