package forge.ai;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import forge.game.Game;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AiCache {

    // stores result + args as vector, per game: each game's AI clears its own game's cache when it
    // starts a decision, never another game's (whose AI would then recompute, drawing extra random
    // numbers from its game's stream and so playing differently than the same seed alone)
    private final static Cache<Game, Multimap<String, List<Object>>> byGame = CacheBuilder.newBuilder().weakKeys().build();

    private static Multimap<String, List<Object>> of(final Game game) {
        return byGame.asMap().computeIfAbsent(game, g -> Multimaps.synchronizedMultimap(ArrayListMultimap.create()));
    }

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    // unwanted collisions between calculations of one game need to be considered:
    // for that you can pass Functions that compare the args
    public static <T> T getCached(Game game, String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        final Multimap<String, List<Object>> dataMap = of(game);
        // TODO would like a good strategy to derive default key, but there's no clean way to obtain the method name
        for (List<Object> cached : Lists.newArrayList(dataMap.get(key))) {
            boolean hit = true;
            for (int i = 0; i < args.length; i++) {
                BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                if (!checker.apply(args[i], cached.get(i + 1))) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return (T) cached.get(0);
            }
        }
        T result = func.get();
        List<Object> cached = Lists.newArrayList(result);
        cached.addAll(Arrays.asList(args));
        dataMap.put(key, cached);
        return result;
    }

    // TODO add different scopes + staleness indicator
    public static void clear(Game game) {
        final Multimap<String, List<Object>> dataMap = byGame.getIfPresent(game);
        if (dataMap != null) {
            dataMap.clear();
        }
    }

}
