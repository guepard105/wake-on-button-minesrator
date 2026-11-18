package com.minestrator.wakeonbutton.wol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Envoi de paquets de reveil (magic packets Wake-on-LAN) en UDP.
 *
 * La classe est volontairement sans etat et utilisable depuis n'importe quel
 * thread (y compris le thread client de Minecraft) : l'envoi bloquant est
 * execute dans le pool commun via {@link CompletableFuture}.
 *
 * Strategie portable, sans droits administrateur :
 * <ol>
 *   <li>diffusion limitee classique ({@code 255.255.255.255}) ;</li>
 *   <li>diffusion specifique a chaque interface (Windows) ;</li>
 *   <li>diffusion limitee liee a chaque interface non-loopback (macOS/Linux) ;</li>
 *   <li>en dernier recours, unicast vers la passerelle supposee.</li>
 * </ol>
 */
public final class WakePacketSender {

    /** Port UDP standard du Wake-on-LAN. */
    public static final int DEFAULT_PORT = 9;

    /** Adresse de diffusion limitee (limited broadcast). */
    public static final String DEFAULT_BROADCAST_ADDRESS = "255.255.255.255";

    /** Timeout des sockets : evite tout blocage indefini sur un envoi. */
    private static final int SOCKET_TIMEOUT_MS = 5_000;

    /** Propriete systeme activant les traces de debogage. */
    private static final String DEBUG_PROPERTY = "wakeonbutton.debug";

    /** Diffusion limitee pre-resolue (evite une resolution a chaque tentative). */
    private static final InetAddress LIMITED_BROADCAST = resolveLimitedBroadcast();

    private WakePacketSender() {
        // Classe utilitaire : pas d'instance.
    }

    /**
     * Envoie un paquet de reveil en UDP vers l'adresse et le port demandes.
     *
     * @param packet           charge utile brute (typiquement le magic packet)
     * @param broadcastAddress adresse de diffusion cible, {@code null} ou vide
     *                         pour utiliser {@link #DEFAULT_BROADCAST_ADDRESS}
     * @param port             port UDP cible, valeur invalide remplacee par
     *                         {@link #DEFAULT_PORT}
     * @return un futur complete normalement des qu'au moins un envoi a reussi,
     *         ou exceptionnellement avec une {@link WakePacketException}
     *         contenant le detail de toutes les tentatives echouees
     */
    public static CompletableFuture<Void> sendMagicPacket(byte[] packet, String broadcastAddress, int port) {
        Objects.requireNonNull(packet, "packet");
        if (packet.length == 0) {
            return CompletableFuture.failedFuture(
                    new WakePacketException("Paquet vide.", List.of()));
        }
        String targetAddress = (broadcastAddress == null || broadcastAddress.isBlank())
                ? DEFAULT_BROADCAST_ADDRESS
                : broadcastAddress.trim();
        int targetPort = (port > 0 && port <= 65535) ? port : DEFAULT_PORT;

        // Le futur est complete a la main pour propager l'exception d'origine
        // (sans encapsulage dans CompletionException).
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                sendBlocking(packet, targetAddress, targetPort);
                future.complete(null);
            } catch (WakePacketException e) {
                future.completeExceptionally(e);
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Envoie un paquet de reveil avec l'adresse et le port par defaut.
     *
     * @param packet charge utile brute (typiquement le magic packet)
     * @return le futur decrit par {@link #sendMagicPacket(byte[], String, int)}
     */
    public static CompletableFuture<Void> sendMagicPacket(byte[] packet) {
        return sendMagicPacket(packet, DEFAULT_BROADCAST_ADDRESS, DEFAULT_PORT);
    }

    /**
     * Envoi bloquant : enchaine les quatre strategies jusqu'a la premiere reussie.
     *
     * @param packet           charge utile a envoyer
     * @param broadcastAddress adresse de diffusion demandee
     * @param port             port UDP cible
     * @throws WakePacketException si aucune strategie n'a abouti
     */
    private static void sendBlocking(byte[] packet, String broadcastAddress, int port)
            throws WakePacketException {
        List<WakePacketException.SendAttempt> failures = new ArrayList<>();

        // Essai 1 : socket standard en diffusion limitee.
        try {
            sendViaSocket(packet, InetAddress.getByName(broadcastAddress), port);
            debug("essai 1 reussi : " + broadcastAddress + ":" + port);
            return;
        } catch (Exception e) {
            failures.add(new WakePacketException.SendAttempt("default", broadcastAddress, e));
            debug("essai 1 echoue (" + broadcastAddress + ") : " + describe(e));
        }

        // Essai 2 : diffusion propre a chaque interface (cas Windows).
        if (tryInterfaceBroadcasts(packet, port, true, failures)) {
            return;
        }

        // Essai 3 : diffusion limitee liee a chaque interface non-loopback.
        if (tryInterfaceBroadcasts(packet, port, false, failures)) {
            return;
        }

        // Essai 4 : dernier recours, unicast vers la passerelle supposee.
        if (tryGatewayUnicast(packet, port, failures)) {
            return;
        }

        throw new WakePacketException("Aucun envoi n'a abouti.", List.copyOf(failures));
    }

    /**
     * Parcourt les interfaces reseau et tente un envoi depuis chacune d'elles.
     *
     * @param packet               charge utile a envoyer
     * @param port                 port UDP cible
     * @param useInterfaceBroadcast {@code true} pour utiliser l'adresse de
     *                             diffusion de l'interface (essai 2),
     *                             {@code false} pour la diffusion limitee
     *                             (essai 3)
     * @param failures             liste alimentee en cas d'echec
     * @return {@code true} si au moins un envoi a reussi
     */
    private static boolean tryInterfaceBroadcasts(byte[] packet, int port,
            boolean useInterfaceBroadcast, List<WakePacketException.SendAttempt> failures) {
        List<NetworkInterface> interfaces;
        try {
            interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            failures.add(new WakePacketException.SendAttempt("<enumeration>", "n/a", e));
            debug("enumeration des interfaces impossible : " + describe(e));
            return false;
        }

        boolean sent = false;
        for (NetworkInterface nif : interfaces) {
            try {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                if (useInterfaceBroadcast && !nif.supportsMulticast()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress local = ia.getAddress();
                    // IPv6 ne connait pas la diffusion : on se limite a IPv4.
                    if (!(local instanceof Inet4Address)) {
                        continue;
                    }
                    InetAddress target;
                    if (useInterfaceBroadcast) {
                        target = ia.getBroadcast();
                        if (target == null) {
                            continue;
                        }
                    } else {
                        target = LIMITED_BROADCAST;
                    }
                    try {
                        sendViaSocket(packet, target, port, local);
                        debug("essai " + (useInterfaceBroadcast ? 2 : 3) + " reussi : "
                                + nif.getName() + " -> " + target.getHostAddress() + ":" + port);
                        sent = true;
                    } catch (Exception e) {
                        failures.add(new WakePacketException.SendAttempt(
                                nif.getName(), target.getHostAddress(), e));
                        debug("essai " + (useInterfaceBroadcast ? 2 : 3) + " echoue : "
                                + nif.getName() + " -> " + target.getHostAddress()
                                + " : " + describe(e));
                    }
                }
            } catch (SocketException e) {
                failures.add(new WakePacketException.SendAttempt(nif.getName(), "n/a", e));
                debug("interface " + nif.getName() + " illisible : " + describe(e));
            }
        }
        return sent;
    }

    /**
     * Dernier recours : envoi unicast vers la passerelle supposee de chaque
     * interface IPv4. Le JDK n'expose pas la table de routage, on applique donc
     * la convention usuelle (premiere adresse utilisable du sous-reseau).
     *
     * @param packet   charge utile a envoyer
     * @param port     port UDP cible
     * @param failures liste alimentee en cas d'echec
     * @return {@code true} si au moins un envoi a reussi
     */
    private static boolean tryGatewayUnicast(byte[] packet, int port,
            List<WakePacketException.SendAttempt> failures) {
        List<NetworkInterface> interfaces;
        try {
            interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            failures.add(new WakePacketException.SendAttempt("<enumeration>", "n/a", e));
            debug("enumeration des interfaces impossible : " + describe(e));
            return false;
        }

        boolean sent = false;
        for (NetworkInterface nif : interfaces) {
            try {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress local = ia.getAddress();
                    if (!(local instanceof Inet4Address)) {
                        continue;
                    }
                    InetAddress gateway = guessGateway(local, ia.getNetworkPrefixLength());
                    if (gateway == null || gateway.equals(local)) {
                        continue;
                    }
                    try {
                        sendViaSocket(packet, gateway, port, local);
                        debug("essai 4 reussi : " + nif.getName() + " -> "
                                + gateway.getHostAddress() + ":" + port);
                        sent = true;
                    } catch (Exception e) {
                        failures.add(new WakePacketException.SendAttempt(
                                nif.getName(), gateway.getHostAddress(), e));
                        debug("essai 4 echoue : " + nif.getName() + " -> "
                                + gateway.getHostAddress() + " : " + describe(e));
                    }
                }
            } catch (SocketException e) {
                failures.add(new WakePacketException.SendAttempt(nif.getName(), "n/a", e));
                debug("interface " + nif.getName() + " illisible : " + describe(e));
            }
        }
        return sent;
    }

    /**
     * Deduit une passerelle plausible a partir de l'adresse locale et du prefixe.
     *
     * @param local  adresse IPv4 locale
     * @param prefix longueur du prefixe reseau
     * @return l'adresse supposee de la passerelle, ou {@code null} si indecidable
     */
    private static InetAddress guessGateway(InetAddress local, int prefix) {
        byte[] address = local.getAddress();
        if (address.length != 4 || prefix < 1 || prefix > 30) {
            return null;
        }
        int mask = -1 << (32 - prefix);
        int localValue = toInt(address);
        int network = localValue & mask;
        // Convention usuelle : la passerelle occupe la premiere adresse du reseau.
        int candidate = network + 1;
        int broadcast = network | ~mask;
        if (candidate == localValue || candidate == broadcast) {
            return null;
        }
        byte[] gateway = new byte[4];
        for (int i = 0; i < 4; i++) {
            gateway[i] = (byte) (candidate >>> (24 - 8 * i));
        }
        try {
            return InetAddress.getByAddress(gateway);
        } catch (IOException e) {
            // Adresse de 4 octets : ne peut pas echouer, par securite on ignore.
            return null;
        }
    }

    /** Convertit une adresse IPv4 (4 octets) en entier non signe. */
    private static int toInt(byte[] address) {
        return ((address[0] & 0xFF) << 24)
                | ((address[1] & 0xFF) << 16)
                | ((address[2] & 0xFF) << 8)
                | (address[3] & 0xFF);
    }

    /**
     * Envoi sur un socket standard, lie implicitement par le systeme.
     *
     * @param packet charge utile a envoyer
     * @param target adresse de destination
     * @param port   port UDP de destination
     * @throws IOException si la diffusion est refusee ou le reseau injoignable
     */
    private static void sendViaSocket(byte[] packet, InetAddress target, int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            configure(socket);
            socket.send(new DatagramPacket(packet, packet.length, target, port));
        }
    }

    /**
     * Envoi depuis une interface precise : le socket est lie a l'adresse locale.
     *
     * @param packet       charge utile a envoyer
     * @param target       adresse de destination
     * @param port         port UDP de destination
     * @param localAddress adresse locale de l'interface a utiliser
     * @throws IOException si la liaison ou l'envoi echoue
     */
    private static void sendViaSocket(byte[] packet, InetAddress target, int port,
            InetAddress localAddress) throws IOException {
        // Socket non liee : permet d'appliquer les options avant le bind().
        try (DatagramSocket socket = new DatagramSocket(null)) {
            configure(socket);
            socket.bind(new InetSocketAddress(localAddress, 0));
            socket.send(new DatagramPacket(packet, packet.length, target, port));
        }
    }

    /**
     * Applique les options communes ; {@code setReuseAddress} doit preceder le bind.
     *
     * @param socket socket a configurer
     * @throws SocketException si une option est refusee par le systeme
     */
    private static void configure(DatagramSocket socket) throws SocketException {
        socket.setReuseAddress(true);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.setBroadcast(true);
    }

    /**
     * Resout l'adresse de diffusion limitee ; 255.255.255.255 est toujours valide.
     *
     * @return l'adresse de diffusion limitee
     */
    private static InetAddress resolveLimitedBroadcast() {
        try {
            return InetAddress.getByName(DEFAULT_BROADCAST_ADDRESS);
        } catch (IOException e) {
            throw new IllegalStateException("Diffusion limitee non resoluble.", e);
        }
    }

    /** Trace de debogage, activee par {@code -Dwakeonbutton.debug=true}. */
    private static void debug(String message) {
        if (Boolean.getBoolean(DEBUG_PROPERTY)) {
            System.err.println("[WakeOnButton] " + message);
        }
    }

    /** Resume compact d'une exception pour les traces. */
    private static String describe(Exception e) {
        String detail = e.getMessage();
        return detail == null
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + " : " + detail;
    }

    /**
     * Exception levee lorsque toutes les strategies d'envoi ont echoue.
     * Elle transporte le detail de chaque tentative pour faciliter le diagnostic.
     */
    public static final class WakePacketException extends Exception {

        private static final long serialVersionUID = 1L;

        /** Detail des tentatives echouees, dans l'ordre d'execution. */
        // transient : l'exception sert au diagnostic en memoire, pas a la serialisation.
        private final transient List<SendAttempt> attempts;

        /**
         * @param message  message principal
         * @param attempts tentatives echouees (copiees)
         */
        public WakePacketException(String message, List<SendAttempt> attempts) {
            super(message + " (" + attempts.size() + " tentative(s) en echec)");
            this.attempts = List.copyOf(attempts);
        }

        /** @return les tentatives echouees, dans l'ordre d'execution */
        public List<SendAttempt> getAttempts() {
            return attempts;
        }

        /**
         * Detail d'une tentative d'envoi ratee.
         *
         * @param interfaceName nom de l'interface visee
         * @param targetAddress adresse de destination visee
         * @param exception     cause de l'echec
         */
        public record SendAttempt(String interfaceName, String targetAddress, Exception exception) {

            @Override
            public String toString() {
                return interfaceName + " -> " + targetAddress + " : "
                        + exception.getClass().getSimpleName()
                        + (exception.getMessage() == null ? "" : " (" + exception.getMessage() + ")");
            }
        }
    }
}
