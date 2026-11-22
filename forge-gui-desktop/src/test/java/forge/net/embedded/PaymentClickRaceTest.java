package forge.net.embedded;

import forge.deck.Deck;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.ActGate;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.FThreads;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.TestUtils;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * A mana payment's sources clicked back to back, each its own act on a thread of its own, as the
 * server's acts are ({@code GameServerHandler}: a "Game BT" thread through the game's {@link ActGate}).
 * A source's mana is played inside its act, and the payment then re-renders on the EDT; that
 * re-render walked the views beside the next source's act, and whichever thread was iterating died
 * with a ConcurrentModificationException (cardtable m-b1bcfd5382d0: an Island clicked 15 ms after a
 * Creeping Tar Pit, in {@code DeltaSyncManager.collectDeltas}).
 */
public class PaymentClickRaceTest {

    @Test(timeOut = 240_000)
    public void aSourceClickedWhileTheLastOneIsShownNeverWalksBesideIt() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        MyRandom.setRandom(new Random(17));

        final ProtocolGuiGameInProcessTest.RecordingRemote remote = new ProtocolGuiGameInProcessTest.RecordingRemote();
        final InputShowRaceTest.WatchedGui gui = new InputShowRaceTest.WatchedGui(remote);
        final Deck humanDeck = new Deck("Seat");
        humanDeck.getMain().add("Island", 24);
        humanDeck.getMain().add("Divination", 16);
        final Deck aiDeck = new Deck("AI");
        aiDeck.getMain().add("Plains", 40);
        final RegisteredPlayer human = new RegisteredPlayer(humanDeck).setPlayer(new LobbyPlayerHuman("Seat"));
        final RegisteredPlayer ai = new RegisteredPlayer(aiDeck).setPlayer(GamePlayerUtil.createAiPlayer("AI"));

        final HostedMatch match = new HostedMatch();
        match.startMatch(GameType.Constructed, null, List.of(human, ai), human, gui);

        final List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
        final java.util.function.Consumer<Runnable> acts = act -> FThreads.invokeInBackgroundThread(() -> {
            try {
                act.run();
            } catch (final Throwable t) {
                thrown.add(t);
            }
        });
        int payments = 0;
        int sourcesClicked = 0;
        boolean conceded = false;
        int landTurn = -1;
        final long deadline = System.currentTimeMillis() + 180_000;
        while (!conceded && System.currentTimeMillis() < deadline) {
            final GuiGameEvent prompt = remote.buttonPrompts.poll(5, TimeUnit.SECONDS);
            if (prompt == null) {
                continue;
            }
            final PlayerView owner = (PlayerView) prompt.getObjects()[0];
            if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
                continue;
            }
            final PlayerControllerHuman seat = (PlayerControllerHuman) gui.getGameController(owner);
            final ActGate gate = ActGate.of(seat.getGame());
            final GameView gv = gui.getGameView();
            if (gv.getTurn() >= 14) {
                conceded = true;
                acts.accept(() -> gate.actFirst(seat, seat::concede));
                continue;
            }
            final Input input = seat.getInputProxy().getInput();
            final boolean mine = owner.equals(gv.getPlayerTurn());
            if (input instanceof InputPayMana) {
                // Every untapped land at once, each its own act: the next one arrives while the last one's mana is shown.
                // Forge drops a source clicked while the last one's mana is still being played (InputPayMana's
                // `locked`), and each re-render of the payment is a prompt here, so its lands are clicked again on it
                final List<CardView> lands = new ArrayList<>();
                for (final CardView c : owner.getBattlefield() == null ? List.<CardView>of() : owner.getBattlefield()) {
                    if (!c.isTapped() && c.getCurrentState().getType().isLand()) {
                        lands.add(c);
                    }
                }
                payments++;
                for (final CardView land : lands) {
                    sourcesClicked++;
                    final long ticket = gate.ticket();
                    acts.accept(() -> gate.act(seat, ticket, () -> seat.selectCard(land, null, null)));
                }
                continue;
            }
            Runnable answer = seat::selectButtonOk;
            if (input instanceof InputPassPriority && mine && (gv.getStack() == null || gv.getStack().isEmpty()) && (gv.getPhase() == PhaseType.MAIN1 || gv.getPhase() == PhaseType.MAIN2)) {
                final CardView land = first(owner, true);
                final CardView divination = first(owner, false);
                if (land != null && landTurn != gv.getTurn()) {
                    landTurn = gv.getTurn();
                    answer = () -> seat.selectCard(land, null, null);
                } else if (divination != null && untappedLands(owner) >= 3) {
                    answer = () -> seat.selectCard(divination, null, null);
                }
            } else if (mine && gv.getPhase() == PhaseType.CLEANUP && owner.getHand() != null && owner.getHand().size() > 7) {
                final CardView discard = owner.getHand().iterator().next();
                answer = () -> seat.selectCard(discard, null, null);
            }
            final long ticket = gate.ticket();
            final Runnable chosen = answer;
            acts.accept(() -> gate.act(seat, ticket, chosen));
        }
        final long end = System.currentTimeMillis() + 30_000;
        while (!match.getGameView().isGameOver() && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }

        System.out.printf("[payment-race] payments=%d sources=%d edtWalks=%d overlaps=%d thrown=%d staleShows=%d%n",
                payments, sourcesClicked, gui.edtWalks, gui.overlaps.size(), thrown.size(), gui.staleShows.size());
        thrown.forEach(t -> t.printStackTrace(System.out));
        gui.overlaps.stream().distinct().limit(10).forEach(o -> System.out.println("[payment-race]   " + o));

        assertTrue(conceded, "the game reached turn 14");
        assertTrue(payments >= 3, "Divination was paid for by hand at least three times");
        assertTrue(match.getGameView().isGameOver(), "the concede ended the game");
        assertEquals(gui.overlaps, List.of(), "no two threads walked the views at once");
        assertEquals(thrown, List.of(), "no act threw");
    }

    /** The first land (or the first non-land) in the seat's hand. */
    private static CardView first(final PlayerView owner, final boolean land) {
        if (owner.getHand() == null) {
            return null;
        }
        for (final CardView c : owner.getHand()) {
            if (c.getCurrentState().getType().isLand() == land) {
                return c;
            }
        }
        return null;
    }

    private static int untappedLands(final PlayerView owner) {
        int n = 0;
        if (owner.getBattlefield() == null) {
            return 0;
        }
        for (final CardView c : owner.getBattlefield()) {
            if (!c.isTapped() && c.getCurrentState().getType().isLand()) {
                n++;
            }
        }
        return n;
    }
}
