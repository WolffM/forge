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

import java.util.ArrayList;
import java.util.List;

/**
 * A set chosen at once ({@link ChosenSet}) keeps the rule the one-at-a-time asks enforced by narrowing
 * their list after every pick.
 */
public class ChosenSetTest extends AITest {

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

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(), gifts));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(counterspell, brainstorm), gifts));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(counterspell, brainstorm, otherCounterspell), gifts));
    }

    @Test
    public void differentManaValues() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility seasons = spell(addCardToZone("Seasons Past", p, ZoneType.Hand));
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Graveyard);
        Card ponder = addCardToZone("Ponder", p, ZoneType.Graveyard);
        Card counterspell = addCardToZone("Counterspell", p, ZoneType.Graveyard);

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bolt, counterspell), seasons));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bolt, ponder), seasons));
    }

    @Test
    public void totalManaValue() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility hulk = svar(addCard("Protean Hulk", p), "TrigChangeZone");
        Card titan = library("Sun Titan", p);
        Card bears = library("Grizzly Bears", p);
        Card ornithopter = library("Ornithopter", p);

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(titan, ornithopter), hulk));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(titan, bears), hulk));
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

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(island, otherIsland), search));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(island, forest), search));
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

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(), delirium));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, bolt, forest), delirium));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, bolt, forest, ornithopter), delirium));
    }

    /** The ability of {@code card} (or one hanging off it, or its SVar {@code svar}) that carries {@code param}. */
    private SpellAbility carrying(Card card, String param, String svar) {
        SpellAbility found = svar == null ? null : AbilityFactory.getAbility(card, svar);
        List<SpellAbility> all = new ArrayList<>(card.getSpellAbilities());
        for (int i = 0; found == null && i < all.size(); i++) {
            for (SpellAbility s = all.get(i); s != null; s = s.getSubAbility()) {
                if (s.hasParam(param)) {
                    found = s;
                    break;
                }
            }
        }
        AssertJUnit.assertNotNull(found);
        found.setActivatingPlayer(card.getController());
        return found;
    }

    @Test
    public void eachTypeSearch() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility verge = carrying(addCard("Krosan Verge", p), "ChangeType", null);
        Card forest = library("Forest", p);
        Card otherForest = library("Forest", p);
        Card plains = library("Plains", p);
        Card bears = library("Grizzly Bears", p);
        List<Card> offered = List.of(forest, otherForest, plains, bears);

        AssertJUnit.assertTrue(ChosenSet.slotted(verge));
        AssertJUnit.assertEquals(2, ChosenSet.slots(verge, offered).size());
        AssertJUnit.assertEquals(List.of(forest, otherForest, plains), new ArrayList<>(ChosenSet.fitting(ChosenSet.slots(verge, offered), offered)));
        AssertJUnit.assertFalse(ChosenSet.mustFill(verge));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(forest, plains), verge, offered));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(forest), verge, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(forest, otherForest), verge, offered));
    }

    @Test
    public void aCardFittingTwoTypesHoldsOne() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility improviser = carrying(addCard("Michelangelo, Improviser", p), "ChangeType", "TrigChangeZone");
        Card arbor = addCardToZone("Dryad Arbor", p, ZoneType.Hand);
        Card bears = addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        Card forest = addCardToZone("Forest", p, ZoneType.Hand);

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(arbor, bears), improviser));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(arbor, forest), improviser));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(arbor, bears, forest), improviser));
        AssertJUnit.assertEquals(2, ChosenSet.matched(ChosenSet.slots(improviser, List.of(arbor, bears, forest)), List.of(arbor, bears, forest)));
    }

    @Test
    public void chooseEachMustFillWhatItCan() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility cataclysm = spell(addCardToZone("Cataclysm", p, ZoneType.Hand));
        Card bears = addCard("Grizzly Bears", p);
        Card ornithopter = addCard("Ornithopter", p);
        Card forest = addCard("Forest", p);
        Card otherForest = addCard("Forest", p);
        List<Card> offered = List.of(bears, ornithopter, forest, otherForest);

        AssertJUnit.assertTrue(ChosenSet.mustFill(cataclysm));
        AssertJUnit.assertEquals(4, ChosenSet.slots(cataclysm, offered).size());
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, ornithopter, forest), cataclysm, offered));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(ornithopter, forest), cataclysm, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, forest), cataclysm, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(ornithopter, forest, otherForest), cataclysm, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(), cataclysm, offered));
    }

    @Test
    public void eachBasicType() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility ruin = spell(addCardToZone("Global Ruin", p, ZoneType.Hand));
        Card forest = addCard("Forest", p);
        Card otherForest = addCard("Forest", p);
        Card island = addCard("Island", p);
        List<Card> offered = List.of(forest, otherForest, island);

        AssertJUnit.assertTrue(ChosenSet.mustFill(ruin));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(forest, island), ruin, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(forest), ruin, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(forest, otherForest, island), ruin, offered));
    }

    @Test
    public void eachDifferentPower() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility judgment = spell(addCardToZone("Celestial Judgment", p, ZoneType.Hand));
        Card bears = addCard("Grizzly Bears", p);
        Card otherBears = addCard("Grizzly Bears", p);
        Card ornithopter = addCard("Ornithopter", p);
        List<Card> offered = List.of(bears, otherBears, ornithopter);

        AssertJUnit.assertEquals(2, ChosenSet.slots(judgment, offered).size());
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(otherBears, ornithopter), judgment, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, otherBears, ornithopter), judgment, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears), judgment, offered));
    }

    @Test
    public void totalPower() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility slaughter = spell(addCardToZone("Slaughter the Strong", p, ZoneType.Hand));
        Card bears = addCard("Grizzly Bears", p);
        Card otherBears = addCard("Grizzly Bears", p);
        Card ornithopter = addCard("Ornithopter", p);

        AssertJUnit.assertTrue(ChosenSet.constrains(slaughter));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, otherBears, ornithopter), slaughter));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, otherBears, addCard("Hill Giant", p)), slaughter));
    }

    @Test
    public void differentPowers() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility vanguard = carrying(addCard("Sigarda's Vanguard", p), "WithDifferentPowers", "TrigChoose");
        Card bears = addCard("Grizzly Bears", p);
        Card otherBears = addCard("Grizzly Bears", p);
        Card ornithopter = addCard("Ornithopter", p);

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, ornithopter), vanguard));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, otherBears), vanguard));
    }

    @Test
    public void digTotalManaValue() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility technique = spell(addCardToZone("Michelangelo's Technique", p, ZoneType.Hand));
        Card titan = library("Sun Titan", p);
        Card bears = library("Grizzly Bears", p);
        Card giant = library("Hill Giant", p);

        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, giant), technique));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(titan, bears), technique));
    }

    @Test
    public void eachColorPair() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility niv = carrying(addCard("Niv-Mizzet Reborn", p), "ForEachColorPair", "TrigDig");
        Card charm = library("Izzet Charm", p);
        Card otherCharm = library("Izzet Charm", p);
        Card bolt = library("Lightning Bolt", p);
        List<Card> offered = List.of(charm, otherCharm, bolt);

        AssertJUnit.assertEquals(10, ChosenSet.slots(niv, offered).size());
        AssertJUnit.assertEquals(List.of(charm, otherCharm), new ArrayList<>(ChosenSet.fitting(ChosenSet.slots(niv, offered), offered)));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(charm), niv, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(charm, otherCharm), niv, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(), niv, offered));
    }

    @Test
    public void copyDifferentNames() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility battle = carrying(addCard("Battle for Bretagard", p), "WithDifferentNames", "DBCopy");
        Card bears = addCard("Grizzly Bears", p);
        Card otherBears = addCard("Grizzly Bears", p);
        Card ornithopter = addCard("Ornithopter", p);

        AssertJUnit.assertTrue(ChosenSet.constrains(battle));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, ornithopter), battle));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, otherBears), battle));
    }

    @Test
    public void aSearchFindsADifferentCardForEachKind() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility passage = carrying(addCardToZone("Guided Passage", p, ZoneType.Hand), "ChangeType", "DBCreature");
        Card arbor = library("Dryad Arbor", p);
        Card forest = library("Forest", p);
        Card bears = library("Grizzly Bears", p);
        Card bolt = library("Lightning Bolt", p);
        List<Card> offered = List.of(arbor, forest, bears, bolt);

        AssertJUnit.assertTrue(ChosenSet.mustFill(passage));
        AssertJUnit.assertTrue(ChosenSet.distinct(passage));
        // Dryad Arbor is one card found: it is the creature or the land, never both, while a Forest could be the land.
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(arbor, bolt), passage, offered));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(arbor, forest, bolt), passage, offered));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(arbor, bears, bolt), passage, offered));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(bears, forest, bolt), passage, offered));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(bears, forest), passage, offered));
    }

    @Test
    public void aSearchFillsWhatDifferentCardsCan() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility passage = carrying(addCardToZone("Guided Passage", p, ZoneType.Hand), "ChangeType", "DBCreature");
        Card arbor = library("Dryad Arbor", p);
        Card forest = library("Forest", p);
        Card bolt = library("Lightning Bolt", p);

        // Dryad Arbor alone is not enough while another land exists: it is the creature, the Forest the land.
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(arbor), passage, List.of(arbor, forest)));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(forest), passage, List.of(arbor, forest)));
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(arbor, forest), passage, List.of(arbor, forest)));
        // With no other creature or land, Dryad Arbor is the one card those kinds can find.
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(arbor, bolt), passage, List.of(arbor, bolt)));
        AssertJUnit.assertFalse(ChosenSet.meets(List.of(arbor), passage, List.of(arbor, bolt)));
    }

    @Test
    public void slotsAreNamedAndChoicesOfPermanentsMayCountTwice() {
        Game game = initAndCreateGame();
        Player p = you(game);
        SpellAbility passage = carrying(addCardToZone("Guided Passage", p, ZoneType.Hand), "ChangeType", "DBCreature");
        SpellAbility ruin = spell(addCardToZone("Global Ruin", p, ZoneType.Hand));
        SpellAbility cataclysm = spell(addCardToZone("Cataclysm", p, ZoneType.Hand));
        SpellAbility niv = carrying(addCard("Niv-Mizzet Reborn", p), "ForEachColorPair", "TrigDig");
        Card volcanic = addCard("Volcanic Island", p);

        List<String> kinds = new ArrayList<>();
        ChosenSet.named(passage, List.of()).forEach(slot -> kinds.add(slot.kind()));
        AssertJUnit.assertEquals(List.of("Creature.YouOwn", "Land.YouOwn", "Card.nonCreature+nonLand+YouOwn"), kinds);
        kinds.clear();
        ChosenSet.named(ruin, List.of(volcanic)).forEach(slot -> kinds.add(slot.kind()));
        AssertJUnit.assertEquals(List.of("Plains", "Island", "Swamp", "Mountain", "Forest"), kinds);
        AssertJUnit.assertEquals("WU", ChosenSet.named(niv, List.of()).get(0).kind());

        AssertJUnit.assertTrue(ChosenSet.distinct(niv));
        AssertJUnit.assertFalse(ChosenSet.distinct(ruin));
        AssertJUnit.assertFalse(ChosenSet.distinct(cataclysm));
        // Global Ruin chooses permanents: Volcanic Island is its Island and its Mountain.
        AssertJUnit.assertTrue(ChosenSet.meets(List.of(volcanic), ruin, List.of(volcanic)));
    }
}
