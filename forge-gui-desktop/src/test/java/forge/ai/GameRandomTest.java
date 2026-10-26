package forge.ai;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.util.MyRandom;

/**
 * A whole AI-vs-AI game draws every random number from its own match's random, on every thread
 * that works for it — its game thread, the AI's eval threads and the attack pool — and never
 * from the JVM-wide one; the same seed plays the same game.
 */
public class GameRandomTest extends AITest {

    /** Counts its draws by the drawing thread's name, with the pool's thread number dropped. */
    static final class Recording extends Random {
        private static final long serialVersionUID = 1L;
        final Map<String, LongAdder> byThread = new ConcurrentHashMap<>();

        Recording(final long seed) {
            super(seed);
        }

        @Override
        protected int next(final int bits) {
            final String t = Thread.currentThread().getName().replaceAll("-\\d+", "-n");
            byThread.computeIfAbsent(t, k -> new LongAdder()).increment();
            return super.next(bits);
        }

        long total() {
            return byThread.values().stream().mapToLong(LongAdder::sum).sum();
        }
    }

    private static Deck deck(final String... cards) {
        final Deck d = new Deck();
        d.getOrCreate(DeckSection.Main).add("Mountain", 17);
        d.getOrCreate(DeckSection.Main).add("Forest", 7);
        for (final String c : cards) {
            d.getOrCreate(DeckSection.Main).add(c, 4);
        }
        return d;
    }

    /** Plays one game to its end on a thread named like Forge's own game threads. */
    private Game play(final Random random) throws InterruptedException {
        final RegisteredPlayer a = new RegisteredPlayer(deck("Lightning Bolt", "Elvish Visionary", "Grizzly Bears", "Shock")).setPlayer(new LobbyPlayerAi("a", null));
        final RegisteredPlayer b = new RegisteredPlayer(deck("Lightning Bolt", "Raging Goblin", "Llanowar Elves", "Shock")).setPlayer(new LobbyPlayerAi("b", null));
        final Match match = new Match(new GameRules(GameType.Constructed), Lists.newArrayList(a, b), "rng");
        match.setRandom(random);
        final Game game = match.createGame();
        game.setNoGUIUser();
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            try {
                match.startGame(game);
            } catch (final Throwable e) {
                failed.set(e);
            }
        }, "Game-rng-test");
        t.start();
        t.join(120_000);
        AssertJUnit.assertFalse("the game ended", t.isAlive());
        AssertJUnit.assertNull(failed.get());
        AssertJUnit.assertTrue(game.isGameOver());
        return game;
    }

    private static String log(final Game game) {
        final StringBuilder sb = new StringBuilder();
        for (final GameLogEntry e : game.getGameLog().getAllEntries()) {
            sb.append(e.type()).append(' ').append(e.message()).append('\n');
        }
        return sb.toString();
    }

    @Test(timeOut = 300_000)
    public void everyThreadWorkingForAGameDrawsFromItsRandom() throws Exception {
        final Random global = MyRandom.getRandom();
        final Recording poison = new Recording(0);
        MyRandom.setRandom(poison);
        try {
            final Recording mine = new Recording(20261025L);
            play(mine);
            // this thread drew once, outside any game, when it made the match (Match's constructor)
            poison.byThread.remove(Thread.currentThread().getName().replaceAll("-\\d+", "-n"));
            AssertJUnit.assertEquals("no draw fell through to the JVM-wide random: " + poison.byThread, 0, poison.total());
            AssertJUnit.assertTrue("the game thread drew: " + mine.byThread, mine.byThread.containsKey("Game-rng-test"));
            AssertJUnit.assertTrue("an AI eval thread drew: " + mine.byThread, mine.byThread.containsKey("Game AI Eval"));
        } finally {
            MyRandom.setRandom(global);
        }
    }

    @Test(timeOut = 300_000)
    public void theSameSeedPlaysTheSameGame() throws Exception {
        final String first = log(play(new Random(7)));
        final String second = log(play(new Random(7)));
        AssertJUnit.assertEquals(first, second);
    }

    @Test
    public void aMatchOwnsItsRandomOrSharesTheBoundOne() {
        final List<RegisteredPlayer> players = Lists.newArrayList(new RegisteredPlayer(new Deck()), new RegisteredPlayer(new Deck()));
        final GameRules rules = new GameRules(GameType.Constructed);

        final Random global = MyRandom.getRandom();
        MyRandom.setRandom(new Random(7));
        final Match first = new Match(rules, players, "a");
        MyRandom.setRandom(new Random(7));
        final Match second = new Match(rules, players, "b");
        AssertJUnit.assertNotSame(MyRandom.getRandom(), first.getRandom());
        AssertJUnit.assertNotSame(first.getRandom(), second.getRandom());
        AssertJUnit.assertEquals("a seeded global seeds the match", first.getRandom().nextLong(), second.getRandom().nextLong());

        final Random bound = new Random(9);
        try (MyRandom.Scope s = MyRandom.bind(bound)) {
            AssertJUnit.assertSame("a copy made inside a game shares its stream", bound, new Match(rules, players, "c").getRandom());
        }

        final Random seeded = new Random(11);
        first.setRandom(seeded);
        AssertJUnit.assertSame(seeded, first.getRandom());
        MyRandom.setRandom(global);
    }

    /** One game's AI starting a decision clears its own game's cache, never another's. */
    @Test
    public void anAiDecisionClearsOnlyItsOwnGamesCache() {
        final Game a = initAndCreateGame();
        final Game b = initAndCreateGame();
        final int[] computed = new int[1];
        final java.util.function.Supplier<Integer> count = () -> ++computed[0];
        AssertJUnit.assertEquals(1, (int) AiCache.getCached(a, "k", count, null));
        AssertJUnit.assertEquals(2, (int) AiCache.getCached(b, "k", count, null));
        AiCache.clear(b);
        AssertJUnit.assertEquals("a's entry survives b's clear", 1, (int) AiCache.getCached(a, "k", count, null));
        AssertJUnit.assertEquals(3, (int) AiCache.getCached(b, "k", count, null));
        AiCache.clear(a);
        AssertJUnit.assertEquals(4, (int) AiCache.getCached(a, "k", count, null));
    }
}
