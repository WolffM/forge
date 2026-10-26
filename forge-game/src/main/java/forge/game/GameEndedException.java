/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2026  Forge Team
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
package forge.game;

/**
 * Unwinds a search done on behalf of a game that has ended ({@link Game#throwIfOver()}).
 *
 * A game ended from another thread (a concede, a host stopping the match) cannot stop
 * the game's own thread while that thread is deep in an AI search: an interrupt reaches
 * only code that waits, and {@code Thread.stop} is gone. The search's loop heads check
 * the game instead and throw this, and {@link GameAction#startGame} catches it, so the
 * game thread leaves the game the way a game that ended normally does.
 */
public final class GameEndedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Game game;

    GameEndedException(final Game game) {
        super("the game ended during the search", null, false, false);
        this.game = game;
    }

    public Game getGame() {
        return game;
    }
}
