package com.minestrator.wakeonbutton.config;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Gestionnaire de configuration chiffrée du mod Wake-On-Button.
 *
 * <p>Deux fichiers sont manipulés dans GAMEDIR (répertoire de jeu, exposé par
 * la propriété système {@code user.dir} quand Minecraft est lancé) :</p>
 * <ul>
 *     <li>{@code config/wakeonbutton.json} : configuration (identifiants
 *     MineStrator chiffrés, clé API chiffrée, préférences d'interface) ;</li>
 *     <li>{@code config/wakeonbutton.key} : clé maître locale générée au premier
 *     lancement (Option B : PBKDF2 sur une graine aléatoire + entropie système).
 *     Ce fichier ne contient jamais les identifiants en clair.</li>
 * </ul>
 *
 * <p>Les identifiants et la clé API sont chiffrés en AES-256-GCM ; la clé de
 * chiffrement est dérivée du mot de passe maître par PBKDF2WithHmacSHA256 avec
 * un sel aléatoire stocké dans le fichier, au format
 * {@code Base64(salt(16) || iv(12) || ciphertext || tag(16))}.</p>
 *
 * <p>Aucune dépendance externe : uniquement le JDK 21 (javax.crypto).</p>
 */
public final class ConfigManager {

    /** Préfixe utilisé pour tous les messages d'erreur sur la sortie standard d'erreur. */
    private static final String LOG_PREFIX = "[wakeonbutton] ";

    /** Taille du sel PBKDF2 en octets. */
    private static final int SALT_BYTES = 16;
    /** Taille de l'IV GCM en octets. */
    private static final int IV_BYTES = 12;
    /** Taille du tag d'authentification GCM en bits (128 bits = 16 octets). */
    private static final int GCM_TAG_BITS = 128;
    /** Taille de la clé AES en bits. */
    private static final int AES_KEY_BITS = 256;

    /** Nombre d'itérations par défaut pour la dérivation de la clé des secrets. */
    private static final int DEFAULT_KDF_ITERATIONS = 100_000;
    /** Nombre d'itérations pour la dérivation de la clé maître (fichier .key). */
    private static final int KEY_FILE_KDF_ITERATIONS = 100_000;
    /** Taille de la graine aléatoire stockée dans le fichier .key. */
    private static final int KEY_SEED_BYTES = 32;

    /** Instance unique (initialisation paresseuse, voir {@link #getInstance()}). */
    private static final ConfigManager INSTANCE = new ConfigManager();

    /** Verrou de lecture/écriture pour l'accès concurrent à l'état en mémoire. */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** Générateur aléatoire cryptographiquement sûr. */
    private final SecureRandom random = new SecureRandom();

    /** Répertoire de jeu (GAMEDIR). */
    private final Path gameDir;
    /** Fichier de configuration JSON. */
    private final Path configFile;
    /** Fichier contenant la clé maître locale. */
    private final Path keyFile;

    // --- État en mémoire (protégé par rwLock) ---

    private String serverId = "511952";
    /** Valeurs chiffrées au format Base64(salt|iv|ciphertext|tag). */
    private String usernameEncrypted = null;
    private String passwordEncrypted = null;
    private String apiKeyEncrypted = null;
    private int kdfIterations = DEFAULT_KDF_ITERATIONS;
    /** Bootstrap : identifiants en clair lus dans le fichier, en attente de chiffrement. */
    private String pendingPlainUsername = null;
    private String pendingPlainPassword = null;
    private boolean showInPauseMenu = true;
    private boolean showInTitleScreen = true;

    /** Identifiants MineStrator en clair (RAM uniquement). */
    public record Credentials(String username, String password, String serverId) {}

    /**
     * Résout le répertoire de jeu : FMLPaths.GAMEDIR sous NeoForge (cas normal
     * du mod), sinon user.dir (tests ou outils hors runtime Minecraft).
     */
    private static Path resolveGameDir() {
        try {
            Path dir = FMLPaths.GAMEDIR.get();
            if (dir != null) {
                return dir;
            }
        } catch (Throwable ignored) {
            // Hors runtime NeoForge : repli sur le working directory.
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }

    /** Constructeur privé : utiliser {@link #getInstance()}. */
    private ConfigManager() {
        this.gameDir = resolveGameDir();
        this.configFile = gameDir.resolve("config").resolve("wakeonbutton.json");
        this.keyFile = gameDir.resolve("config").resolve("wakeonbutton.key");
    }

    /** Renvoie l'instance unique (création thread-safe garantie par le champ statique final). */
    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Chargement / sauvegarde
    // ------------------------------------------------------------------

    /**
     * Charge la configuration depuis le disque. Si le fichier est absent ou
     * illisible, les valeurs par défaut (sûres) sont conservées.
     */
    public void load() {
        rwLock.writeLock().lock();
        try {
            if (!Files.isRegularFile(configFile)) {
                log("aucune configuration existante (" + configFile + "), valeurs par défaut");
                return;
            }
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            if (!(parseJson(json) instanceof Map<?, ?> root)) {
                log("configuration illisible (objet JSON attendu)");
                return;
            }

            Map<?, ?> minestrator = asMap(root.get("minestrator"));
            if (minestrator != null) {
                serverId = asString(minestrator.get("server_id"), serverId);
                usernameEncrypted = asString(minestrator.get("username_encrypted"), usernameEncrypted);
                passwordEncrypted = asString(minestrator.get("password_encrypted"), passwordEncrypted);
                apiKeyEncrypted = asString(minestrator.get("api_key_encrypted"), apiKeyEncrypted);
                kdfIterations = asInt(minestrator.get("kdf_iterations"), kdfIterations);
                // Bootstrap : identifiants éventuellement fournis en clair par le pack.
                String plainUser = asString(minestrator.get("username"), null);
                if (plainUser != null && !plainUser.isBlank()) {
                    pendingPlainUsername = plainUser;
                }
                String plainPass = asString(minestrator.get("password"), null);
                if (plainPass != null && !plainPass.isBlank()) {
                    pendingPlainPassword = plainPass;
                }
            }

            Map<?, ?> ui = asMap(root.get("ui"));
            if (ui != null) {
                showInPauseMenu = asBoolean(ui.get("show_in_pause_menu"), showInPauseMenu);
                showInTitleScreen = asBoolean(ui.get("show_in_title_screen"), showInTitleScreen);
            }
        } catch (IOException e) {
            log("échec de lecture de la configuration : " + e.getMessage());
        } catch (RuntimeException e) {
            log("configuration invalide : " + e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Sauvegarde la configuration de façon atomique : écriture dans un fichier
     * temporaire du même répertoire, puis renommage.
     */
    public void save() {
        rwLock.readLock().lock();
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = toJson();
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Certains systèmes de fichiers ne supportent pas le rename atomique.
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log("échec d'écriture de la configuration : " + e.getMessage());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // Clé maître locale (Option B)
    // ------------------------------------------------------------------

    /** Indique s'il s'agit du premier lancement : le fichier de clé est absent. */
    public boolean isFirstRun() {
        rwLock.readLock().lock();
        try {
            return !Files.isRegularFile(keyFile);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Charge la clé maître, ou renvoie {@code null} si le fichier de clé
     * n'existe pas (premier lancement : l'interface doit alors proposer la
     * première configuration).
     */
    public char[] loadMasterKey() {
        rwLock.readLock().lock();
        try {
            if (!Files.isRegularFile(keyFile)) {
                return null;
            }
            String content = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            byte[] blob = Base64.getDecoder().decode(content);
            if (blob.length < SALT_BYTES + KEY_SEED_BYTES) {
                log("fichier de clé invalide (trop court)");
                return null;
            }
            byte[] salt = new byte[SALT_BYTES];
            byte[] seed = new byte[KEY_SEED_BYTES];
            System.arraycopy(blob, 0, salt, 0, SALT_BYTES);
            System.arraycopy(blob, SALT_BYTES, seed, 0, KEY_SEED_BYTES);
            return deriveMasterKey(seed, salt);
        } catch (IOException | RuntimeException e) {
            log("échec de lecture de la clé maître : " + e.getMessage());
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Charge la clé maître et la génère si elle est absente (premier lancement).
     * Renvoie {@code null} en cas d'échec.
     */
    public char[] loadOrCreateMasterKey() {
        char[] existing = loadMasterKey();
        if (existing != null) {
            return existing;
        }
        rwLock.writeLock().lock();
        try {
            // Double vérification : un autre appel a pu créer la clé entre-temps.
            if (Files.isRegularFile(keyFile)) {
                String content = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                byte[] blob = Base64.getDecoder().decode(content);
                byte[] salt = new byte[SALT_BYTES];
                byte[] seed = new byte[KEY_SEED_BYTES];
                System.arraycopy(blob, 0, salt, 0, SALT_BYTES);
                System.arraycopy(blob, SALT_BYTES, seed, 0, KEY_SEED_BYTES);
                return deriveMasterKey(seed, salt);
            }

            // Graine aléatoire + entropie système (horodatage, identité JVM).
            byte[] seed = new byte[KEY_SEED_BYTES];
            random.nextBytes(seed);
            mixSystemEntropy(seed);
            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);

            byte[] blob = new byte[SALT_BYTES + KEY_SEED_BYTES];
            System.arraycopy(salt, 0, blob, 0, SALT_BYTES);
            System.arraycopy(seed, 0, blob, SALT_BYTES, KEY_SEED_BYTES);

            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
            Files.writeString(tmp, Base64.getEncoder().encodeToString(blob),
                    StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log("clé maître générée : " + keyFile);
            return deriveMasterKey(seed, salt);
        } catch (IOException | RuntimeException e) {
            log("échec de génération de la clé maître : " + e.getMessage());
            return null;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Mélange de l'entropie système dans la graine (horodatage, identité JVM). */
    private void mixSystemEntropy(byte[] seed) {
        long nano = System.nanoTime();
        long millis = System.currentTimeMillis();
        String identity = System.getProperty("java.version", "") + System.getProperty("user.name", "");
        byte[] extra = (nano + ":" + millis + ":" + identity).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < extra.length; i++) {
            seed[i % seed.length] ^= extra[i];
        }
    }

    /** Dérive la clé maître (chaîne Base64) depuis la graine et le sel. */
    private char[] deriveMasterKey(byte[] seed, byte[] salt) {
        char[] password = hex(seed).toCharArray();
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, KEY_FILE_KDF_ITERATIONS, AES_KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key).toCharArray();
        } catch (Exception e) {
            log("échec de dérivation de la clé maître : " + e.getMessage());
            return null;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    // ------------------------------------------------------------------
    // Chiffrement AES-GCM
    // ------------------------------------------------------------------

    /**
     * Chiffre un texte (identifiant, mot de passe, clé API) avec la clé dérivée
     * du mot de passe maître.
     *
     * @return la chaîne {@code Base64(salt(16) || iv(12) || ciphertext || tag(16))},
     *         ou {@code null} en cas d'échec ou de mot de passe vide.
     */
    public String encryptToken(String plaintext, char[] masterPassword) {
        if (plaintext == null) {
            return null;
        }
        if (masterPassword == null || masterPassword.length == 0) {
            log("chiffrement impossible : mot de passe maître absent");
            return null;
        }
        try {
            int iterations;
            rwLock.readLock().lock();
            try {
                iterations = kdfIterations > 0 ? kdfIterations : DEFAULT_KDF_ITERATIONS;
            } finally {
                rwLock.readLock().unlock();
            }

            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            byte[] keyBytes = deriveAesKey(masterPassword, salt, iterations);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                // doFinal renvoie ciphertext || tag
                byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

                byte[] blob = new byte[salt.length + iv.length + ciphertext.length];
                System.arraycopy(salt, 0, blob, 0, salt.length);
                System.arraycopy(iv, 0, blob, salt.length, iv.length);
                System.arraycopy(ciphertext, 0, blob, salt.length + iv.length, ciphertext.length);
                return Base64.getEncoder().encodeToString(blob);
            } finally {
                java.util.Arrays.fill(keyBytes, (byte) 0);
            }
        } catch (Exception e) {
            log("échec du chiffrement du jeton : " + e.getMessage());
            return null;
        }
    }

    /**
     * Déchiffre une chaîne produite par {@link #encryptToken(String, char[])}.
     *
     * @return le texte en clair, ou {@code null} en cas d'échec (fichier corrompu,
     *         clé incompatible...).
     */
    public String decryptToken(String encrypted, char[] masterPassword) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        if (masterPassword == null || masterPassword.length == 0) {
            log("déchiffrement impossible : mot de passe maître absent");
            return null;
        }
        try {
            int iterations;
            rwLock.readLock().lock();
            try {
                iterations = kdfIterations > 0 ? kdfIterations : DEFAULT_KDF_ITERATIONS;
            } finally {
                rwLock.readLock().unlock();
            }

            byte[] blob = Base64.getDecoder().decode(encrypted);
            if (blob.length < SALT_BYTES + IV_BYTES + 16) {
                log("jeton chiffré invalide (trop court)");
                return null;
            }
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[blob.length - SALT_BYTES - IV_BYTES];
            System.arraycopy(blob, 0, salt, 0, SALT_BYTES);
            System.arraycopy(blob, SALT_BYTES, iv, 0, IV_BYTES);
            System.arraycopy(blob, SALT_BYTES + IV_BYTES, ciphertext, 0, ciphertext.length);

            byte[] keyBytes = deriveAesKey(masterPassword, salt, iterations);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] plain = cipher.doFinal(ciphertext);
                return new String(plain, StandardCharsets.UTF_8);
            } finally {
                java.util.Arrays.fill(keyBytes, (byte) 0);
            }
        } catch (Exception e) {
            log("échec du déchiffrement du jeton (fichier corrompu ou clé incompatible)");
            return null;
        }
    }

    /** Dérivation PBKDF2WithHmacSHA256 de la clé AES-256. */
    private byte[] deriveAesKey(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, AES_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // ------------------------------------------------------------------
    // API de configuration
    // ------------------------------------------------------------------

    /**
     * Renvoie les identifiants, ou {@code null} s'ils sont absents ou
     * indéchiffrables.
     *
     * <p>Si un bootstrap en clair est en attente (fourni par le pack), il est
     * d'abord chiffré puis effacé.</p>
     */
    public Credentials getCredentials(char[] masterKey) {
        String userEnc;
        String passEnc;
        boolean pending;
        rwLock.readLock().lock();
        try {
            userEnc = usernameEncrypted;
            passEnc = passwordEncrypted;
            pending = pendingPlainUsername != null || pendingPlainPassword != null;
        } finally {
            rwLock.readLock().unlock();
        }
        if (isEmpty(userEnc) && isEmpty(passEnc) && pending) {
            if (migratePlainCredentials()) {
                rwLock.readLock().lock();
                try {
                    userEnc = usernameEncrypted;
                    passEnc = passwordEncrypted;
                } finally {
                    rwLock.readLock().unlock();
                }
            }
        }
        if (isEmpty(userEnc) || isEmpty(passEnc)) {
            return null;
        }
        String user = decryptToken(userEnc, masterKey);
        String pass = decryptToken(passEnc, masterKey);
        if (isEmpty(user) || isEmpty(pass)) {
            return null;
        }
        rwLock.readLock().lock();
        try {
            return new Credentials(user, pass, serverId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Chiffre les éventuels identifiants bootstrap en clair, les réécrit et les
     * efface de la mémoire.
     *
     * @return {@code true} si une migration a eu lieu
     */
    public boolean migratePlainCredentials() {
        String plainUser;
        String plainPass;
        rwLock.writeLock().lock();
        try {
            plainUser = pendingPlainUsername;
            plainPass = pendingPlainPassword;
            pendingPlainUsername = null;
            pendingPlainPassword = null;
        } finally {
            rwLock.writeLock().unlock();
        }
        if (isEmpty(plainUser) && isEmpty(plainPass)) {
            return false;
        }
        char[] key = loadOrCreateMasterKey();
        if (key == null) {
            log("migration des identifiants impossible : cle maitre indisponible");
            return false;
        }
        try {
            String userEnc = isEmpty(plainUser) ? null : encryptToken(plainUser, key);
            String passEnc = isEmpty(plainPass) ? null : encryptToken(plainPass, key);
            if (userEnc == null && passEnc == null) {
                return false;
            }
            rwLock.writeLock().lock();
            try {
                if (userEnc != null) {
                    usernameEncrypted = userEnc;
                }
                if (passEnc != null) {
                    passwordEncrypted = passEnc;
                }
            } finally {
                rwLock.writeLock().unlock();
            }
            save();
            log("identifiants MineStrator en clair migres vers la forme chiffree");
            return true;
        } finally {
            java.util.Arrays.fill(key, '\0');
        }
    }

    /** Clé API MineStrator en cache, ou {@code null} si absente/indéchiffrable. */
    public String getApiKey(char[] masterKey) {
        String encrypted;
        rwLock.readLock().lock();
        try {
            encrypted = apiKeyEncrypted;
        } finally {
            rwLock.readLock().unlock();
        }
        if (isEmpty(encrypted)) {
            return null;
        }
        String apiKey = decryptToken(encrypted, masterKey);
        return isEmpty(apiKey) ? null : apiKey;
    }

    /** Chiffre et mémorise la clé API, puis sauvegarde. */
    public void setApiKey(String apiKey, char[] masterKey) {
        char[] key = masterKey;
        boolean localKey = false;
        if (key == null) {
            key = loadOrCreateMasterKey();
            localKey = true;
        }
        if (key == null) {
            log("enregistrement de la cle API impossible : cle maitre indisponible");
            return;
        }
        try {
            String encrypted = isEmpty(apiKey) ? null : encryptToken(apiKey, key);
            rwLock.writeLock().lock();
            try {
                apiKeyEncrypted = encrypted;
            } finally {
                rwLock.writeLock().unlock();
            }
            save();
        } finally {
            if (localKey) {
                java.util.Arrays.fill(key, '\0');
            }
        }
    }

    /** Chiffre et mémorise un couple identifiant/mot de passe, puis sauvegarde. */
    public void setCredentials(String username, String password, char[] masterKey) {
        char[] key = masterKey;
        boolean localKey = false;
        if (key == null) {
            key = loadOrCreateMasterKey();
            localKey = true;
        }
        if (key == null) {
            log("enregistrement des identifiants impossible : cle maitre indisponible");
            return;
        }
        try {
            String userEnc = isEmpty(username) ? null : encryptToken(username, key);
            String passEnc = isEmpty(password) ? null : encryptToken(password, key);
            rwLock.writeLock().lock();
            try {
                usernameEncrypted = userEnc;
                passwordEncrypted = passEnc;
            } finally {
                rwLock.writeLock().unlock();
            }
            save();
        } finally {
            if (localKey) {
                java.util.Arrays.fill(key, '\0');
            }
        }
    }

    public String getServerId() {
        rwLock.readLock().lock();
        try {
            return serverId;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void setServerId(String serverId) {
        rwLock.writeLock().lock();
        try {
            this.serverId = serverId == null ? "" : serverId.trim();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean isShowInPauseMenu() {
        rwLock.readLock().lock();
        try {
            return showInPauseMenu;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void setShowInPauseMenu(boolean value) {
        rwLock.writeLock().lock();
        try {
            showInPauseMenu = value;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean isShowInTitleScreen() {
        rwLock.readLock().lock();
        try {
            return showInTitleScreen;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void setShowInTitleScreen(boolean value) {
        rwLock.writeLock().lock();
        try {
            showInTitleScreen = value;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Chemin du fichier de configuration JSON. */
    public Path getConfigFile() {
        return configFile;
    }

    /** Chemin du fichier de clé maître. */
    public Path getKeyFile() {
        return keyFile;
    }

    // ------------------------------------------------------------------
    // Sérialisation JSON (JDK pur, schéma fixe)
    // ------------------------------------------------------------------

    /**
     * Construit la représentation JSON de l'état courant (ordre stable).
     * Les champs en clair {@code username}/{@code password} ne sont jamais écrits.
     */
    private String toJson() {
        rwLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\n");
            sb.append("  \"minestrator\": {\n");
            sb.append("    \"server_id\": ").append(quote(serverId)).append(",\n");
            if (usernameEncrypted != null) {
                sb.append("    \"username_encrypted\": ").append(quote(usernameEncrypted)).append(",\n");
            }
            if (passwordEncrypted != null) {
                sb.append("    \"password_encrypted\": ").append(quote(passwordEncrypted)).append(",\n");
            }
            if (apiKeyEncrypted != null) {
                sb.append("    \"api_key_encrypted\": ").append(quote(apiKeyEncrypted)).append(",\n");
            }
            sb.append("    \"kdf_iterations\": ").append(kdfIterations).append("\n");
            sb.append("  },\n");
            sb.append("  \"ui\": {\n");
            sb.append("    \"show_in_pause_menu\": ").append(showInPauseMenu).append(",\n");
            sb.append("    \"show_in_title_screen\": ").append(showInTitleScreen).append("\n");
            sb.append("  }\n");
            sb.append("}\n");
            return sb.toString();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Échappe une chaîne pour JSON ; {@code null} devient {@code null} JSON. */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Analyse un document JSON minimal (objets, tableaux, chaînes, nombres, booléens). */
    private static Object parseJson(String src) {
        JsonReader reader = new JsonReader(src);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw new IllegalArgumentException("contenu résiduel après l'objet JSON");
        }
        return value;
    }

    /** Lecteur JSON minimaliste suffisant pour le schéma de configuration. */
    private static final class JsonReader {
        private final String src;
        private int pos;

        JsonReader(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("fin de document inattendue");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("objet JSON non terminé");
                }
                char c = src.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("virgule attendue dans l'objet JSON");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new java.util.ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("tableau JSON non terminé");
                }
                char c = src.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("virgule attendue dans le tableau JSON");
                }
            }
        }

        private String readString() {
            skipWhitespace();
            if (atEnd() || src.charAt(pos) != '"') {
                throw new IllegalArgumentException("chaîne JSON attendue");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("chaîne JSON non terminée");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("échappement JSON incomplet");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("échappement \\u incomplet");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("échappement JSON inconnu : \\" + esc);
                }
            }
        }

        private Double readNumber() {
            int start = pos;
            while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("valeur JSON inattendue");
            }
            return Double.valueOf(src.substring(start, pos));
        }

        private Object readLiteral(String literal, Object value) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("littéral JSON inattendu");
            }
            pos += literal.length();
            return value;
        }

        private void expect(char c) {
            if (atEnd() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("caractère '" + c + "' attendu");
            }
            pos++;
        }
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    private static Map<?, ?> asMap(Object value) {
        return (value instanceof Map<?, ?> map) ? map : null;
    }

    private static String asString(Object value, String fallback) {
        return (value instanceof String s) ? s : fallback;
    }

    private static int asInt(Object value, int fallback) {
        return (value instanceof Double d) ? d.intValue() : fallback;
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        return (value instanceof Boolean b) ? b : fallback;
    }

    /** Indique si une chaîne est nulle ou vide. */
    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /** Convertit des octets en chaîne hexadécimale minuscule. */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Restreint les permissions du fichier à son propriétaire (si POSIX supporté). */
    private static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows ou système non POSIX : on ignore silencieusement.
        }
    }

    /** Journalisation minimale sur la sortie d'erreur (pas de dépendance logger). */
    private static void log(String message) {
        System.err.println(LOG_PREFIX + message);
    }
}
