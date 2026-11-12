package forge.net.embedded;

import forge.deck.Deck;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.input.ActGate;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.TestUtils;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * A prompt is shown on the EDT ({@code InputProxy}), and showing it walks the game's views for its
 * selection, while the seat's answer arrives on another thread and runs through the game's
 * {@link ActGate}, as the server's acts do. An act pressed ahead runs the moment the game waits on
 * the next prompt, before or while the EDT shows it. Answered within milliseconds, the act's own walk (the
 * selection {@code stop} sends) and the game thread it releases ran beside that walk, and whichever
 * thread was iterating died with a ConcurrentModificationException: on the game thread, the match.
 */
public class InputShowRaceTest {

    /** Records every time two threads walk the views at once. The EDT's walk is held, as on a big board. */
    static final class WatchedGui extends ProtocolGuiGame {
        private final Map<Thread, Integer> walking = new ConcurrentHashMap<>();
        final List<String> overlaps = Collections.synchronizedList(new ArrayList<>());
        /** The inputs an act has answered, by selection id: none is shown again. */
        final java.util.Set<Integer> answered = ConcurrentHashMap.newKeySet();
        final List<Integer> staleShows = Collections.synchronizedList(new ArrayList<>());
        volatile int edtWalks;

        WatchedGui(final IRemote remote) {
            super(remote);
        }

        @Override
        public void setSelection(final Selection selection) {
            if (selection.input() != 0 && answered.contains(selection.input())) {
                staleShows.add(selection.input());
            }
            super.setSelection(selection);
        }

        @Override
        public void updateGameView() {
            final Thread me = Thread.currentThread();
            walking.merge(me, 1, Integer::sum);
            try {
                for (final Thread other : walking.keySet()) {
                    if (other != me) {
                        overlaps.add(me.getName() + " walked while " + other.getName() + " did");
                    }
                }
                if (SwingUtilities.isEventDispatchThread()) {
                    edtWalks++;
                    Thread.sleep(15);
                }
                super.updateGameView();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                walking.computeIfPresent(me, (t, n) -> n == 1 ? null : n - 1);
            }
        }
    }

    /** {@code act}, recording the input it answered once it is done (an OK or a pick stops it). */
    private static Runnable answered(final WatchedGui gui, final PlayerControllerHuman seat, final Runnable act) {
        return () -> {
            final forge.gamemodes.match.input.Input input = seat.getInputProxy().getInput();
            act.run();
            if (input instanceof forge.gamemodes.match.input.InputSyncronizedBase answeredInput && answeredInput.isDone()) {
                gui.answered.add(answeredInput.getSelectionId());
            }
        };
    }

    @Test(timeOut = 240_000)
    public void anActNeverWalksBesideAPromptBeingShown() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        MyRandom.setRandom(new Random(11));

        final ProtocolGuiGameInProcessTest.RecordingRemote remote = new ProtocolGuiGameInProcessTest.RecordingRemote();
        final WatchedGui gui = new WatchedGui(remote);
        final Deck humanDeck = new Deck("Seat");
        humanDeck.getMain().add("Plains", 40);
        final Deck aiDeck = new Deck("AI");
        aiDeck.getMain().add("Plains", 40);
        final RegisteredPlayer human = new RegisteredPlayer(humanDeck).setPlayer(new LobbyPlayerHuman("Seat"));
        final RegisteredPlayer ai = new RegisteredPlayer(aiDeck).setPlayer(GamePlayerUtil.createAiPlayer("AI"));

        final HostedMatch match = new HostedMatch();
        match.startMatch(GameType.Constructed, null, List.of(human, ai), human, gui);

        // The seat answers every prompt the moment it reaches the remote, on a thread of its own, as a client does,
        final ExecutorService pool = Executors.newCachedThreadPool();
        final List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
        final java.util.function.Consumer<Runnable> acts = act -> pool.execute(() -> {
            try {
                act.run();
            } catch (final Throwable t) {
                thrown.add(t);
            }
        });
        int answered = 0;
        boolean conceded = false;
        int landTurn = -1;
        final long deadline = System.currentTimeMillis() + 180_000;
        try {
            while (!conceded && System.currentTimeMillis() < deadline) {
                final GuiGameEvent prompt = remote.buttonPrompts.poll(5, TimeUnit.SECONDS);
                if (prompt == null) {
                    continue;
                }
                final PlayerView owner = (PlayerView) prompt.getObjects()[0];
                if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
                    continue;
                }
                assertTrue(gui.getGameController(owner) instanceof PlayerControllerHuman, "the seat is a human controller");
                final PlayerControllerHuman seat = (PlayerControllerHuman) gui.getGameController(owner);
                final ActGate gate = ActGate.of(seat.getGame());
                final GameView gv = gui.getGameView();
                answered++;
                if (gv.getTurn() >= 8) {
                    conceded = true;
                    acts.accept(() -> gate.actFirst(seat, seat::concede));
                    continue;
                }
                // A land each turn, a card to discard at cleanup, otherwise OK
                final boolean mine = owner.equals(gv.getPlayerTurn());
                final CardView fromHand = owner.getHand() == null || owner.getHand().isEmpty() ? null : owner.getHand().iterator().next();
                final Runnable answer;
                if (fromHand != null && mine && gv.getPhase() == PhaseType.MAIN1 && landTurn != gv.getTurn()) {
                    landTurn = gv.getTurn();
                    answer = () -> seat.selectCard(fromHand, null, null);
                } else if (fromHand != null && mine && gv.getPhase() == PhaseType.CLEANUP && owner.getHand().size() > 7) {
                    answer = () -> seat.selectCard(fromHand, null, null);
                } else {
                    answer = seat::selectButtonOk;
                }
                // and presses again at once, ahead of the next prompt: held until the game waits on that one
                final long ticket = gate.ticket();
                final long ahead = gate.ticket();
                acts.accept(() -> gate.act(seat, ticket, answered(gui, seat, answer)));
                acts.accept(() -> gate.act(seat, ahead, answered(gui, seat, seat::selectButtonOk)));
            }
            final long end = System.currentTimeMillis() + 30_000;
            while (!match.getGameView().isGameOver() && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        System.out.printf("[show-race] answered=%d edtWalks=%d overlaps=%d thrown=%d staleShows=%d%n", answered, gui.edtWalks, gui.overlaps.size(), thrown.size(), gui.staleShows.size());
        thrown.forEach(t -> t.printStackTrace(System.out));
        gui.overlaps.stream().distinct().limit(10).forEach(o -> System.out.println("[show-race]   " + o));

        assertTrue(conceded, "the game reached turn 8");
        assertTrue(match.getGameView().isGameOver(), "the concede ended the game");
        assertTrue(gui.edtWalks > 0, "prompts were shown with a walk on the EDT");
        assertEquals(gui.overlaps, List.of(), "no two threads walked the views at once");
        assertEquals(thrown, List.of(), "no act threw");
        assertEquals(gui.staleShows, List.of(), "no input was shown again once an act had answered it");
    }
}
