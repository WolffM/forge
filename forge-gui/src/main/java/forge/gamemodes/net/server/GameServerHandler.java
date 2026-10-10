package forge.gamemodes.net.server;

import forge.gamemodes.net.GameProtocolHandler;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import io.netty.channel.ChannelHandlerContext;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GameServerHandler extends GameProtocolHandler<IGameController> implements IHasForgeLog {

    private final FServerManager server = FServerManager.getInstance();

    /**
     * The player's input acts — clicks and button presses — that reach the game's current Input.
     * Two of them on two threads at once ran two InputPassPriority.onCardSelected side by side on
     * one game and left a view unserializable (every later encode failed); a client's acts are
     * taken in the order they arrived, one at a time, on this connection's own thread.
     */
    private static final Set<ProtocolMethod> ORDERED_ACTS = EnumSet.of(
            ProtocolMethod.selectCard, ProtocolMethod.selectPlayer, ProtocolMethod.selectAbility,
            ProtocolMethod.selectButtonOk, ProtocolMethod.selectButtonCancel, ProtocolMethod.useMana,
            ProtocolMethod.undoLastAction, ProtocolMethod.alphaStrike);

    private final ExecutorService acts = Executors.newSingleThreadExecutor(r -> {
        // "Game…" so FThreads.isGuiThread() reads it as a game thread, like invokeInBackgroundThread's.
        final Thread t = new Thread(r, "Game acts");
        t.setDaemon(true);
        return t;
    });

    GameServerHandler() {
        super(false);
    }

    @Override
    protected void runInBackground(final ProtocolMethod protocolMethod, final Runnable toRun) {
        if (ORDERED_ACTS.contains(protocolMethod)) {
            acts.execute(toRun);
        } else {
            super.runInBackground(protocolMethod, toRun);
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        acts.shutdownNow();
        super.channelInactive(ctx);
    }

    private RemoteClient getClient(final ChannelHandlerContext ctx) {
        return server.getClient(ctx.channel());
    }

    @Override
    protected ReplyPool getReplyPool(final ChannelHandlerContext ctx) {
        return getClient(ctx).getReplyPool();
    }

    @Override
    protected IRemote getRemote(final ChannelHandlerContext ctx) {
        return getClient(ctx);
    }

    @Override
    protected IGameController getToInvoke(final ChannelHandlerContext ctx) {
        final RemoteClient client = getClient(ctx);
        return client != null ? server.getController(client.getIndex()) : null;
    }

    @Override
    protected void beforeCall(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Object[] args) {
        if (protocolMethod == ProtocolMethod.requestResync) {
            RemoteClient client = getClient(ctx);
            if (client != null) {
                IGuiGame gui = server.getGui(client.getIndex());
                if (gui instanceof RemoteClientGuiGame netGui) {
                    netLog.debug("[DeltaSync] Resync requested by client {}, deferring to game thread", client.getIndex());
                    netGui.setResyncPending();
                } else {
                    netLog.warn("[DeltaSync] GUI is not RemoteClientGuiGame, cannot resync client {}", client.getIndex());
                }
            }
        }
    }

}
