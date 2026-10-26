package forge.gamemodes.net.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import forge.game.Game;

import forge.gamemodes.match.input.InputSynchronized;
import forge.player.PlayerControllerHuman;

/**
 * The servers this process is hosting, if any (a process may host several, one per match). Match and input code ask here instead of
 * calling {@link FServerManager#getInstance()}, so a game that never hosts never loads
 * the network stack.
 */
public final class HostingServer {

    @FunctionalInterface
    public interface AfkTimeout {
        AfkTimeout NOOP = () -> {};
        void cancel();
    }

    /** What match and input code need from a running server. */
    public interface Server {
        AfkTimeout armAfkTimeout(PlayerControllerHuman controller, InputSynchronized input);

        /** Whether this server's match is playing {@code game}. */
        boolean hosts(Game game);
    }

    private static final Set<Server> ACTIVE = ConcurrentHashMap.newKeySet();

    private HostingServer() {
    }

    static void add(final Server server) {
        ACTIVE.add(server);
    }

    static void remove(final Server server) {
        ACTIVE.remove(server);
    }

    public static boolean isHosting() {
        return !ACTIVE.isEmpty();
    }

    /** The AFK timeout of the server hosting the controller's game; none when no server hosts it. */
    public static AfkTimeout armAfkTimeout(final PlayerControllerHuman controller, final InputSynchronized input) {
        for (final Server server : ACTIVE) {
            if (server.hosts(controller.getGame())) {
                return server.armAfkTimeout(controller, input);
            }
        }
        return AfkTimeout.NOOP;
    }
}
