package forge.game.ability.effects;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.List;

/**
 * A constrained search chosen as one set ({@link ChangeZoneEffect#meetsSearchConstraints}) keeps the
 * rule the one-at-a-time search enforced by narrowing its list after every pick.
 */
public class ChangeZoneSearchConstraintsTest extends AITest {

    private Player you(Game game) {
        return game.getPlayers().get(0);
    }

    private SpellAbility spell(Card card) {
        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(card.getController());
        return sa;
    }

    private SpellAbility svar(Card card, String name) {
        SpellAbility sa = AbilityFactory.getAbility(card, name);
        sa.setActivatingPlayer(card.getController());
        return sa;
    }

    private Card library(String name, Player p) {
        return addCardToZone(name, p, ZoneType.Library);
    }

    @Test
    public void differentNames() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility gifts = spell(addCardToZone("Gifts Ungiven", p, ZoneType.Hand));
        Card counterspell = library("Counterspell", p);
        Card otherCounterspell = library("Counterspell", p);
        Card brainstorm = library("Brainstorm", p);

        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(), gifts));
        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(counterspell, brainstorm), gifts));
        AssertJUnit.assertFalse(ChangeZoneEffect.meetsSearchConstraints(List.of(counterspell, brainstorm, otherCounterspell), gifts));
    }

    @Test
    public void differentManaValues() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility seasons = spell(addCardToZone("Seasons Past", p, ZoneType.Hand));
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Graveyard);
        Card ponder = addCardToZone("Ponder", p, ZoneType.Graveyard);
        Card counterspell = addCardToZone("Counterspell", p, ZoneType.Graveyard);

        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(bolt, counterspell), seasons));
        AssertJUnit.assertFalse(ChangeZoneEffect.meetsSearchConstraints(List.of(bolt, ponder), seasons));
    }

    @Test
    public void totalManaValue() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility hulk = svar(addCard("Protean Hulk", p), "TrigChangeZone");
        Card titan = library("Sun Titan", p);
        Card bears = library("Grizzly Bears", p);
        Card ornithopter = library("Ornithopter", p);

        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(titan, ornithopter), hulk));
        AssertJUnit.assertFalse(ChangeZoneEffect.meetsSearchConstraints(List.of(titan, bears), hulk));
    }

    @Test
    public void shareLandType() {
        Game game = initAndCreateGame();
        Player p = you(game);
        Card landscape = addCard("Myriad Landscape", p);
        SpellAbility search = null;
        for (SpellAbility sa : landscape.getSpellAbilities()) {
            if (sa.hasParam("ShareLandType")) {
                search = sa;
            }
        }
        AssertJUnit.assertNotNull(search);
        search.setActivatingPlayer(p);
        Card island = library("Island", p);
        Card otherIsland = library("Island", p);
        Card forest = library("Forest", p);

        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(island, otherIsland), search));
        AssertJUnit.assertFalse(ChangeZoneEffect.meetsSearchConstraints(List.of(island, forest), search));
    }

    @Test
    public void cardTypesAmongThem() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility delirium = svar(addCard("Winter, Cynical Opportunist", p), "TrigExileDelirium");
        Card bears = addCardToZone("Grizzly Bears", p, ZoneType.Graveyard);
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Graveyard);
        Card forest = addCardToZone("Forest", p, ZoneType.Graveyard);
        Card ornithopter = addCardToZone("Ornithopter", p, ZoneType.Graveyard);

        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(), delirium));
        AssertJUnit.assertFalse(ChangeZoneEffect.meetsSearchConstraints(List.of(bears, bolt, forest), delirium));
        AssertJUnit.assertTrue(ChangeZoneEffect.meetsSearchConstraints(List.of(bears, bolt, forest, ornithopter), delirium));
    }
}
