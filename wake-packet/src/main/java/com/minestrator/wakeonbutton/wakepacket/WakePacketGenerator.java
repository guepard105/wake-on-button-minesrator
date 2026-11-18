package com.minestrator.wakeonbutton.wakepacket;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Generateur de paquets de reveil (wake packets) pour MineStrator.
 *
 * Ce sous-projet est volontairement autonome : JDK pur, aucune dependance
 * externe, afin de pouvoir etre compile et lance isolement.
 *
 * Le paquet produit est un Magic Packet Wake-on-LAN :
 * <ul>
 *   <li>6 octets 0xFF (synchronisation) ;</li>
 *   <li>16 repetitions de l'adresse MAC (6 octets chacune) ;</li>
 *   <li>eventuellement le mot de passe SecureOn (4 ou 6 octets).</li>
 * </ul>
 *
 * Sortie : base64 (sans retour a la ligne) avec {@code --output-base64},
 * sinon les octets bruts sur stdout.
 */
public final class WakePacketGenerator {

    /** Nombre d'octets 0xFF en tete du paquet. */
    private static final int MAGIC_PREFIX_LENGTH = 6;

    /** Nombre de repetitions de l'adresse MAC. */
    private static final int MAC_REPEAT_COUNT = 16;

    /** Taille d'une adresse MAC. */
    private static final int MAC_LENGTH = 6;

    /** Motif d'une adresse MAC : aa:bb:cc:dd:ee:ff ou aa-bb-cc-dd-ee-ff. */
    private static final String MAC_PATTERN = "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$";

    /** Motif d'un mot de passe SecureOn : 4 a 6 octets hexadecimaux. */
    private static final String PASSWORD_PATTERN = "^([0-9A-Fa-f]{2}){4,6}$";

    /** Code de sortie : succes. */
    private static final int EXIT_OK = 0;

    /** Code de sortie : argument manquant ou invalide. */
    private static final int EXIT_ARGS = 1;

    /** Code de sortie : adresse MAC invalide. */
    private static final int EXIT_MAC = 2;

    /** Code de sortie : mot de passe invalide. */
    private static final int EXIT_PASSWORD = 3;

    /** Code de sortie : erreur d'ecriture sur la sortie standard. */
    private static final int EXIT_IO = 4;

    private WakePacketGenerator() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    /**
     * Execute la commande et retourne le code de sortie.
     *
     * @param args arguments de la ligne de commande
     * @return 0 en cas de succes, un code non nul sinon
     */
    static int run(String[] args) {
        String macValue = null;
        String passwordValue = null;
        boolean outputBase64 = false;

        // Boucle simple : un argument par tour, aucun parseur externe.
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return EXIT_OK;
            } else if ("--output-base64".equals(arg)) {
                outputBase64 = true;
            } else if (arg.startsWith("--mac=")) {
                macValue = arg.substring("--mac=".length());
            } else if (arg.startsWith("--password=")) {
                passwordValue = arg.substring("--password=".length());
            } else {
                return error(EXIT_ARGS, msg("argument inconnu : " + arg,
                        "unknown argument: " + arg));
            }
        }

        if (macValue == null) {
            return error(EXIT_ARGS, msg("argument obligatoire manquant : --mac=<MAC_ADDRESS>",
                    "missing required argument: --mac=<MAC_ADDRESS>"));
        }

        byte[] mac;
        try {
            mac = parseMac(macValue);
        } catch (IllegalArgumentException e) {
            return error(EXIT_MAC, e.getMessage());
        }

        byte[] password = null;
        if (passwordValue != null) {
            try {
                password = parsePassword(passwordValue);
            } catch (IllegalArgumentException e) {
                return error(EXIT_PASSWORD, e.getMessage());
            }
        }

        return writePacket(buildPacket(mac, password), outputBase64);
    }

    /**
     * Ecrit le paquet sur la sortie standard.
     *
     * @param packet       paquet a ecrire
     * @param outputBase64 {@code true} pour une sortie base64, sinon octets bruts
     * @return le code de sortie
     */
    private static int writePacket(byte[] packet, boolean outputBase64) {
        try {
            if (outputBase64) {
                // Aucun retour a la ligne : la sortie reste une chaine unique.
                System.out.print(Base64.getEncoder().encodeToString(packet));
            } else {
                System.out.write(packet);
            }
            System.out.flush();
            // PrintStream avale les IOException : checkError() les rend visibles.
            if (System.out.checkError()) {
                return error(EXIT_IO, msg("ecriture sur la sortie standard impossible",
                        "cannot write to standard output"));
            }
        } catch (IOException e) {
            return error(EXIT_IO, msg(
                    "ecriture sur la sortie standard impossible : " + e.getMessage(),
                    "cannot write to standard output: " + e.getMessage()));
        }
        return EXIT_OK;
    }

    /**
     * Signale une erreur sur stderr et retourne le code de sortie associe.
     *
     * @param code    code de sortie
     * @param message description de l'erreur, deja localisee
     * @return le code de sortie fourni
     */
    private static int error(int code, String message) {
        System.err.println((isFrench() ? "ERREUR : " : "ERROR: ") + message);
        System.err.println(msg("Utilisez --help pour afficher l'aide.",
                "Use --help to display help."));
        return code;
    }

    /** @return {@code true} si la locale par defaut est le francais. */
    private static boolean isFrench() {
        return "fr".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }

    /**
     * Choisit le message selon la locale par defaut.
     *
     * @param french  message francais
     * @param english message anglais
     * @return le message localise
     */
    private static String msg(String french, String english) {
        return isFrench() ? french : english;
    }

    /**
     * Analyse une adresse MAC au format {@code aa:bb:cc:dd:ee:ff} ou
     * {@code aa-bb-cc-dd-ee-ff}.
     *
     * @param value adresse MAC textuelle
     * @return les 6 octets de l'adresse
     * @throws IllegalArgumentException si le format est invalide
     */
    static byte[] parseMac(String value) {
        String trimmed = value.trim();
        if (!trimmed.matches(MAC_PATTERN)) {
            throw new IllegalArgumentException(msg(
                    "adresse MAC invalide (attendu aa:bb:cc:dd:ee:ff ou aa-bb-cc-dd-ee-ff) : "
                            + value,
                    "invalid MAC address (expected aa:bb:cc:dd:ee:ff or aa-bb-cc-dd-ee-ff): "
                            + value));
        }
        byte[] mac = new byte[MAC_LENGTH];
        for (int i = 0; i < MAC_LENGTH; i++) {
            // Deux caracteres hexadecimaux suivis du separateur (sauf le dernier).
            mac[i] = (byte) Integer.parseInt(trimmed.substring(i * 3, i * 3 + 2), 16);
        }
        return mac;
    }

    /**
     * Analyse un mot de passe SecureOn exprime en hexadecimal : 4 ou 6 octets.
     *
     * @param value mot de passe hexadecimal (8 ou 12 caracteres)
     * @return les octets du mot de passe
     * @throws IllegalArgumentException si le format est invalide
     */
    static byte[] parsePassword(String value) {
        String trimmed = value.trim();
        if (!trimmed.matches(PASSWORD_PATTERN)) {
            throw new IllegalArgumentException(msg(
                    "mot de passe invalide (4 ou 6 octets hexadecimaux attendus) : " + value,
                    "invalid password (4 or 6 hexadecimal bytes expected): " + value));
        }
        int length = trimmed.length() / 2;
        // Le motif autorise 5 octets : on n'accepte que 4 ou 6 (SecureOn).
        if (length != 4 && length != 6) {
            throw new IllegalArgumentException(msg(
                    "mot de passe invalide (4 ou 6 octets attendus) : " + value,
                    "invalid password (4 or 6 bytes expected): " + value));
        }
        byte[] password = new byte[length];
        for (int i = 0; i < length; i++) {
            password[i] = (byte) Integer.parseInt(trimmed.substring(i * 2, i * 2 + 2), 16);
        }
        return password;
    }

    /**
     * Construit le Magic Packet : 6 x 0xFF, 16 x MAC, puis le mot de passe
     * eventuel.
     *
     * @param mac      adresse MAC deja validee (6 octets)
     * @param password mot de passe SecureOn (4 ou 6 octets) ou {@code null}
     * @return le paquet complet
     */
    static byte[] buildPacket(byte[] mac, byte[] password) {
        int passwordLength = password == null ? 0 : password.length;
        byte[] packet = new byte[MAGIC_PREFIX_LENGTH + MAC_REPEAT_COUNT * MAC_LENGTH
                + passwordLength];
        Arrays.fill(packet, 0, MAGIC_PREFIX_LENGTH, (byte) 0xFF);
        for (int i = 0; i < MAC_REPEAT_COUNT; i++) {
            System.arraycopy(mac, 0, packet, MAGIC_PREFIX_LENGTH + i * MAC_LENGTH, MAC_LENGTH);
        }
        if (password != null) {
            System.arraycopy(password, 0, packet,
                    MAGIC_PREFIX_LENGTH + MAC_REPEAT_COUNT * MAC_LENGTH, password.length);
        }
        return packet;
    }

    /** Affiche l'aide sur la sortie standard. */
    private static void printHelp() {
        System.out.println("WakePacketGenerator - genere un Magic Packet Wake-on-LAN");
        System.out.println();
        System.out.println("Usage :");
        System.out.println("  java -jar wake-packet-all.jar --mac=<MAC_ADDRESS> [options]");
        System.out.println();
        System.out.println("Options :");
        System.out.println("  --mac=<MAC_ADDRESS>   adresse MAC cible (obligatoire),");
        System.out.println("                        format aa:bb:cc:dd:ee:ff ou aa-bb-cc-dd-ee-ff");
        System.out.println("  --password=<HEX>      mot de passe SecureOn optionnel, 4 ou 6 octets");
        System.out.println("                        hexadecimaux (ex : 11223344 ou 112233445566)");
        System.out.println("  --output-base64       paquet en base64 au lieu des octets bruts");
        System.out.println("  --help, -h            affiche cette aide");
        System.out.println();
        System.out.println("Sortie :");
        System.out.println("  Par defaut les octets bruts du paquet sont ecrits sur stdout");
        System.out.println("  (102 octets sans mot de passe, 106 ou 108 avec).");
        System.out.println();
        System.out.println("Codes de sortie :");
        System.out.println("  0  succes");
        System.out.println("  1  arguments invalides (argument inconnu ou --mac manquant)");
        System.out.println("  2  adresse MAC invalide");
        System.out.println("  3  mot de passe invalide");
        System.out.println("  4  erreur d'ecriture sur la sortie standard");
    }
}
