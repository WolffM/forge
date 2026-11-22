package forge.ai;

import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * A spell its caster can pay for only through a mana conversion is offered: the scan and the click
 * read the conversions a payment of it would spend mana under, and leave the pool as they found it.
 */
public class AvailableActionsManaConversionTest extends AITest {

    private static boolean offered(Player player, Card card) {
        return AvailableActions.collectActionable(player, 5000).contains(card.getView())
                && !AvailableActions.offered(player, card.getAllPossibleAbilities(player, true)).isEmpty();
    }

    @Test
    public void staticConversionOffersTheSpell() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCard("Plains", player);
        Card bolt = addCardToZone("Lightning Bolt", player, ZoneType.Hand);

        AssertJUnit.assertFalse("white mana does not pay {R}", offered(player, bolt));

        addCard("Sunglasses of Urza", player); // You may spend white mana as though it were red mana.
        AssertJUnit.assertTrue("white mana pays {R} under the conversion", offered(player, bolt));
        AssertJUnit.assertEquals("the pool is left with no conversion", (byte) ManaAtom.WHITE,
                player.getManaPool().getPossibleColorUses((byte) ManaAtom.WHITE));
    }

    @Test
    public void permissionConversionOffersAnOpponentsExiledSpell() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addCard("Plains", player);
        // You may cast spells from among cards exiled with it, spending mana as though it were mana of any color.
        Card devourer = addCard("Intellect Devourer", player);
        Card bolt = addCardToZone("Lightning Bolt", opponent, ZoneType.Exile);
        bolt.setExiledWith(devourer);
        devourer.addExiledCard(bolt);
        game.getAction().checkStaticAbilities();

        AssertJUnit.assertTrue("the permission's conversion pays {R} with white mana", offered(player, bolt));
        AssertJUnit.assertEquals("the pool is left with no conversion", (byte) ManaAtom.WHITE,
                player.getManaPool().getPossibleColorUses((byte) ManaAtom.WHITE));
    }
}
