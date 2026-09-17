/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.view;

import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.NetworkLogConfig;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.model.FModel;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code forge server} — host a Forge lobby with no window and no local player UI.
 *
 * <p>The same objects the desktop client hosts with: {@link FServerManager} binds the Netty
 * listener and {@link ServerGameLobby} holds the seats, wired the way
 * {@code NetConnectUtil.host} wires them. What it does not have is an {@code ILobbyView} — a
 * dedicated server has no screen to draw the lobby on — so the lobby's update listener does the
 * one thing the view was there to trigger: broadcast the lobby state to the connected clients.
 *
 * <p><b>Scope.</b> This hosts a lobby: clients connect, take seats, chat, and see each other. It
 * does not start a match on its own. Seat 0 of a {@link ServerGameLobby} is
 * {@link LobbySlotType#LOCAL}, and {@code FServerManager.getGui(0)} answers a LOCAL seat with
 * {@code GuiBase.getInterface().getNewGuiGame()} — a Swing GUI, which is exactly what a headless
 * process cannot supply. Seating that slot with an AI, or teaching the lobby to have no local seat
 * at all, is the next piece of work and is deliberately not guessed at here.
 *
 * <p>Runs until interrupted. {@code -t} bounds the run so a smoke test can assert the bind and
 * then exit, rather than needing a signal.
 */
public final class DedicatedServer {

    private DedicatedServer() {
    }

    private static void usage() {
        System.out.println("Dedicated server mode.");
        System.out.println("  forge server [-p <port>] [-t <seconds>] [--upnp]");
        System.out.println();
        System.out.println("  -p <port>     port to listen on (default: the NET_PORT preference, "
                + ForgeNetPreferences.FNetPref.NET_PORT.getDefault() + ")");
        System.out.println("  -t <seconds>  stop after this many seconds instead of running until "
                + "interrupted");
        System.out.println("  --upnp        ask the gateway to map the port (default: do not; the "
                + "stock default is ASK, which a headless process cannot answer)");
    }

    /**
     * @param args the whole command line, {@code "server"} included, as {@code Main} received it.
     */
    public static void start(final String[] args) {
        Integer port = null;
        long runSeconds = 0;
        boolean upnp = false;

        for (int i = 1; i < args.length; i++) {
            final String a = args[i];
            switch (a) {
                case "-p":
                case "--port":
                    port = Integer.parseInt(requireValue(args, ++i, a));
                    break;
                case "-t":
                case "--seconds":
                    runSeconds = Long.parseLong(requireValue(args, ++i, a));
                    break;
                case "--upnp":
                    upnp = true;
                    break;
                case "-h":
                case "--help":
                    usage();
                    return;
                default:
                    System.err.println("Unknown option: " + a);
                    usage();
                    System.exit(2);
            }
        }

        // UPnP defaults to ASK, and ASK opens a modal dialog from inside startServer(). There is
        // nobody here to answer it and no display to draw it on, so decide it up front.
        final String upnpPref = upnp ? "ALWAYS" : "NEVER";
        FModel.initialize(null, preferences -> {
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, upnpPref);
            return null;
        });
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, upnpPref);

        final int boundPort = port != null
                ? port
                : FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);

        NetworkLogConfig.activateNetworkLogging();

        final FServerManager server = FServerManager.getInstance();
        final ServerGameLobby lobby = new ServerGameLobby();

        server.startServer(boundPort);
        if (!server.isHosting()) {
            // startServer() logs the cause and returns; without this the process would sit in the
            // wait below looking like a server that is up.
            System.err.println("Failed to start server on port " + boundPort + ".");
            System.exit(1);
        }
        server.setLobby(lobby);

        // The desktop host passes lobby updates to its view and then to updateLobbyState(). With no
        // view, the broadcast is the whole job: without it a client's seat change never reaches the
        // other clients.
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                server.updateLobbyState();
            }

            @Override
            public void update(final int slot, final LobbySlotType type) {
            }
        });

        server.setLobbyListener(new ILobbyListener() {
            @Override
            public void message(final String source, final String message,
                                final ChatMessage.MessageType type) {
                System.out.println("[chat/" + type + "] " + source + ": " + message);
            }

            @Override
            public void update(final GameLobbyData state, final int slot) {
                // The lobby is held here, not received over the wire; nothing to apply.
            }

            @Override
            public void close() {
                // A server is not told to close by a peer.
            }

            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });

        System.out.printf("Dedicated server listening on port %d (hosting=%s, upnpMapped=%s, "
                        + "seats=%d)%n",
                boundPort, server.isHosting(), server.isUPnPMapped(), lobby.getNumberOfSlots());
        for (final Map.Entry<String, String> address
                : FServerManager.getAllLocalAddresses().entrySet()) {
            System.out.printf("  %s\t%s%n", address.getValue(), address.getKey());
        }

        final CountDownLatch stop = new CountDownLatch(1);
        final AtomicBoolean jvmExiting = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            jvmExiting.set(true);
            stop.countDown();
            System.out.println("Shutting down.");
        }, "dedicated-server-shutdown"));

        try {
            if (runSeconds > 0) {
                System.out.printf("Running for %d s, then stopping.%n", runSeconds);
                stop.await(runSeconds, TimeUnit.SECONDS);
            } else {
                System.out.println("Running until interrupted.");
                stop.await();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Only from a normal exit. FServerManager registers its own shutdown hook, and
        // stopServer() tries to remove it — which throws IllegalStateException once the JVM is
        // already shutting down.
        if (!jvmExiting.get()) {
            server.stopServer();
            System.out.println("Stopped.");
        }
    }

    private static String requireValue(final String[] args, final int i, final String option) {
        if (i >= args.length) {
            System.err.println(option + " needs a value.");
            usage();
            System.exit(2);
        }
        return args[i];
    }
}
