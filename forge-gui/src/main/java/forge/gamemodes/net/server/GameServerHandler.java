package forge.gamemodes.net.server;

import forge.gamemodes.match.input.ActGate;
import forge.gamemodes.net.GameProtocolHandler;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.player.PlayerControllerHuman;
import io.netty.channel.ChannelHandlerContext;

import java.util.EnumSet;
import java.util.Set;

final class GameServerHandler extends GameProtocolHandler<IGameController> implements IHasForgeLog {

    private final FServerManager server = FServerManager.getInstance();

    /** A player's acts on the game's current Input: taken one at a time per game, in arrival order (ActGate). */
    private static final Set<ProtocolMethod> ACTS = EnumSet.of(
            ProtocolMethod.selectCard, ProtocolMethod.selectPlayer, ProtocolMethod.selectAbility,
            ProtocolMethod.selectButtonOk, ProtocolMethod.selectButtonCancel, ProtocolMethod.useMana,
            ProtocolMethod.undoLastAction, ProtocolMethod.alphaStrike);

    GameServerHandler() {
        super(false);
    }

    @Override
    protected void runInBackground(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Runnable toRun) {
        if (ACTS.contains(protocolMethod) && getToInvoke(ctx) instanceof PlayerControllerHuman pch) {
            final ActGate gate = ActGate.of(pch.getGame());
            final long ticket = gate.ticket();
            super.runInBackground(ctx, protocolMethod, () -> gate.run(ticket, toRun));
        } else {
            super.runInBackground(ctx, protocolMethod, toRun);
        }
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
