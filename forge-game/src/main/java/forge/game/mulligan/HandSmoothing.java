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
package forge.game.mulligan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.Lists;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Filtered hands ({@code StaticData.getFilteredHandsEnabled}): before a hand is
 * drawn, a second candidate is shuffled up and one of the two is kept, judged by
 * how far its land count is from the library's own land ratio. Only the order
 * of the library changes; the caller still draws the hand itself.
 *
 * The opening hand keeps the closer candidate outright ({@link #strict}). A
 * mulligan redraw is only weighted toward it ({@link #weighted}), so a mulligan
 * stays a real reshuffle rather than a second guaranteed average hand.
 */
public final class HandSmoothing {
    private HandSmoothing() {}

    /** Keep whichever candidate is closer to the library's land ratio; a tie keeps the library as it is. */
    public static void strict(Player player, int handSize) {
        List<Card> library = libraryOf(player);
        List<Card> other = shuffled(library);
        float ratio = landRatio(library);
        if (distance(library, handSize, ratio) > distance(other, handSize, ratio)) {
            player.getZone(ZoneType.Library).setCards(other);
        }
    }

    /**
     * Keep each candidate with probability proportional to {@code 1 / (1 + distance)}:
     * a hand on the ratio against one two lands off is kept three times in four,
     * and two equally close candidates are a coin flip.
     */
    public static void weighted(Player player, int handSize) {
        List<Card> library = libraryOf(player);
        List<Card> other = shuffled(library);
        float ratio = landRatio(library);
        double keep = 1.0 / (1.0 + distance(library, handSize, ratio));
        double swap = 1.0 / (1.0 + distance(other, handSize, ratio));
        if (MyRandom.getRandom().nextDouble() * (keep + swap) < swap) {
            player.getZone(ZoneType.Library).setCards(other);
        }
    }

    private static List<Card> libraryOf(Player player) {
        return Lists.newArrayList(player.getZone(ZoneType.Library).getCards().threadSafeIterable());
    }

    private static List<Card> shuffled(List<Card> library) {
        List<Card> copy = new ArrayList<>(library);
        Collections.shuffle(copy, MyRandom.getRandom());
        return copy;
    }

    private static float landRatio(List<Card> library) {
        return library.isEmpty() ? 0 : (float) lands(library) / library.size();
    }

    /** How many lands the top {@code handSize} cards are from the ratio's expected count. */
    private static float distance(List<Card> library, int handSize, float ratio) {
        List<Card> hand = library.subList(0, Math.min(handSize, library.size()));
        return Math.abs(ratio * hand.size() - lands(hand));
    }

    private static int lands(List<Card> cards) {
        int n = 0;
        for (Card c : cards) {
            if (c.isLand()) {
                n++;
            }
        }
        return n;
    }
}
