package forge.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class MyRandomTest {
    private Random global;

    @BeforeMethod
    public void saveGlobal() {
        global = MyRandom.getRandom();
    }

    @AfterMethod
    public void restoreGlobal() {
        MyRandom.setRandom(global);
    }

    @Test
    public void outsideAGameDrawsFromTheGlobal() {
        final Random g = new Random(1);
        MyRandom.setRandom(g);
        AssertJUnit.assertNull(MyRandom.getBound());
        AssertJUnit.assertSame(g, MyRandom.getRandom());
    }

    @Test
    public void bindScopesNestAndRestore() {
        final Random g = new Random(1);
        final Random a = new Random(2);
        final Random b = new Random(3);
        MyRandom.setRandom(g);
        try (MyRandom.Scope outer = MyRandom.bind(a)) {
            AssertJUnit.assertSame(a, MyRandom.getRandom());
            try (MyRandom.Scope inner = MyRandom.bind(b)) {
                AssertJUnit.assertSame(b, MyRandom.getRandom());
                // setRandom inside a game changes only the global, never the game's stream
                MyRandom.setRandom(new Random(4));
                AssertJUnit.assertSame(b, MyRandom.getRandom());
            }
            AssertJUnit.assertSame(a, MyRandom.getRandom());
            try (MyRandom.Scope none = MyRandom.bind(null)) {
                AssertJUnit.assertNull(MyRandom.getBound());
            }
            AssertJUnit.assertSame(a, MyRandom.getRandom());
        }
        AssertJUnit.assertNull(MyRandom.getBound());
    }

    /**
     * A pool reuses its threads across games (AiAttackController's attack pool, Forge's game
     * thread pool): the binding travels with each task from its submitter, never with the thread.
     */
    @Test
    public void aPooledTaskRunsWithItsSubmittersBindingOnAReusedThread() throws Exception {
        final Random g = new Random(1);
        MyRandom.setRandom(g);
        final Random gameA = new Random(2);
        final Random gameB = new Random(3);
        final ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            final Callable<Object[]> probe = () -> new Object[] {Thread.currentThread(), MyRandom.getRandom()};
            final List<Future<Object[]>> seen = new ArrayList<>();
            try (MyRandom.Scope a = MyRandom.bind(gameA)) {
                seen.add(pool.submit(MyRandom.propagate(probe)));
            }
            try (MyRandom.Scope b = MyRandom.bind(gameB)) {
                seen.addAll(pool.invokeAll(List.of(MyRandom.propagate(probe))));
            }
            seen.add(pool.submit(MyRandom.propagate(probe))); // submitted outside any game
            seen.add(pool.submit(probe)); // not wrapped: the reused thread kept no binding
            final Thread t = (Thread) seen.get(0).get()[0];
            for (final Future<Object[]> f : seen) {
                AssertJUnit.assertSame("one reused pool thread", t, f.get()[0]);
            }
            AssertJUnit.assertSame(gameA, seen.get(0).get()[1]);
            AssertJUnit.assertSame(gameB, seen.get(1).get()[1]);
            AssertJUnit.assertSame(g, seen.get(2).get()[1]);
            AssertJUnit.assertSame(g, seen.get(3).get()[1]);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The AI eval thread: a fresh thread running a task created on the game thread. */
    @Test
    public void aSpawnedThreadRunsWithItsSpawnersBinding() throws Exception {
        final Random game = new Random(2);
        final Random[] seen = new Random[1];
        try (MyRandom.Scope s = MyRandom.bind(game)) {
            final Thread t = new Thread(MyRandom.propagate(() -> { seen[0] = MyRandom.getRandom(); }), "Game AI Eval");
            t.start();
            t.join();
        }
        AssertJUnit.assertSame(game, seen[0]);
    }
}
