package forge.ai.attacking;

import forge.ai.AITest;
import forge.ai.AiAttackController;
import forge.ai.AiBlockController;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameEndedException;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * An AI search on behalf of a game that has ended stops at its next loop head, so a host that
 * ends a match gets the game's thread back even when an interrupt cannot reach the search.
 */
public class SearchEndsWithGameTest extends AITest {

    private Game boardOfBears(final int each) {
        final Game game = initAndCreateGame();
        for (final Player p : game.getPlayers()) {
            for (int i = 0; i < each; i++) {
                addCard("Grizzly Bears", p).setSickness(false);
            }
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(1));
        game.getAction().checkStateEffects(true);
        return game;
    }

    @Test
    public void attackSearchRunsWhileTheGameDoesAndUnwindsOnceItIsOver() {
        final Game game = boardOfBears(3);
        final Player ai = game.getPlayers().get(1);

        new AiAttackController(ai).declareAttackers(new Combat(ai));

        game.setGameOver(GameEndReason.Draw);
        AssertJUnit.assertTrue(game.isGameOver());
        try {
            new AiAttackController(ai).declareAttackers(new Combat(ai));
            AssertJUnit.fail("an attack search for an ended game should unwind");
        } catch (final GameEndedException e) {
            AssertJUnit.assertSame(game, e.getGame());
        }
    }

    @Test
    public void blockSearchUnwindsOnceTheGameIsOver() {
        final Game game = boardOfBears(3);
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Combat combat = new Combat(opponent);
        for (final Card c : opponent.getCreaturesInPlay()) {
            combat.addAttacker(c, ai);
        }

        game.setGameOver(GameEndReason.Draw);
        try {
            new AiBlockController(ai, false).assignBlockersForCombat(combat);
            AssertJUnit.fail("a block search for an ended game should unwind");
        } catch (final GameEndedException e) {
            AssertJUnit.assertSame(game, e.getGame());
        }
    }
}
