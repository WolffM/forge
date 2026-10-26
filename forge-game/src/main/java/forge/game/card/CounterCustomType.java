package forge.game.card;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import com.google.common.collect.Maps;

public record CounterCustomType(String keyword) implements CounterType {
    // every game in the process fills this, so it is locked
    private static final Map<String, CounterCustomType> sMap = Collections.synchronizedMap(Maps.newHashMap());

    public static CounterCustomType get(String s) {
        return sMap.computeIfAbsent(s, CounterCustomType::new);
    }

    public static Set<CounterType> getValues() {
        synchronized (sMap) {
            return new LinkedHashSet<CounterType>(sMap.values());
        }
    }
    
    @Override
    public String toString() {
        return keyword;
    }

    public String getName() {
        return keyword;
    }
}
