package forge.game;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;

import forge.GameCommand;
import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardStateName;
import forge.card.GamePieceType;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.ability.SpellAbilityEffect;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.ability.effects.EffectEffect;
import forge.game.card.*;
import forge.game.card.token.TokenInfo;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventCombatChanged;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.item.PaperToken;
import forge.util.TextUtil;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

public class GameState {
    private static final Map<ZoneType, String> ZONES = new HashMap<>();
    static {
        ZONES.put(ZoneType.Battlefield, "battlefield");
        ZONES.put(ZoneType.Hand, "hand");
        ZONES.put(ZoneType.Graveyard, "graveyard");
        ZONES.put(ZoneType.Library, "library");
        ZONES.put(ZoneType.Exile, "exile");
        ZONES.put(ZoneType.Command, "command");
        ZONES.put(ZoneType.Sideboard, "sideboard");
    }

    static class PlayerState {
        private int life = -1;
        private String counters = "";
        private String manaPool = "";
        private String persistentMana = "";
        private int landsPlayed = 0;
        private int landsPlayedLastTurn = 0;
        private int numRingTemptedYou = 0;
        private int speed = 0;
        private String precast = null;
        private String putOnStack = null;
        private final Map<ZoneType, String> cardTexts = new EnumMap<>(ZoneType.class);
    }
    private final List<PlayerState> playerStates = new ArrayList<>();

    private boolean puzzleCreatorState = false;

    private final Map<Integer, Card> idToCard = new HashMap<>();
    private final Map<Card, Integer> cardToAttachId = new HashMap<>();
    private final Map<Card, Player> cardToEnchantPlayerId = new HashMap<>();
    private final Map<Card, Integer> markedDamage = new HashMap<>();
    private final Map<Card, List<String>> cardToChosenClrs = new HashMap<>();
    private final Map<Card, CardCollection> cardToChosenCards = new HashMap<>();
    private final Map<Card, String> cardToChosenType = new HashMap<>();
    private final Map<Card, String> cardToChosenType2 = new HashMap<>();
    private final Map<Card, List<String>> cardToRememberedId = new HashMap<>();
    private final Map<Card, List<String>> cardToImprintedId = new HashMap<>();
    private final Map<Card, List<String>> cardToMergedCards = new HashMap<>();
    private final Map<Card, List<String>> cardToNamedCard = new HashMap<>();
    private final Map<Card, String> cardToExiledWithId = new HashMap<>();
    private final Map<Card, Card> cardAttackMap = new HashMap<>();
    /** Each card's BaseController:, GainedControl: and ControlUntil: entries, in written order, put on by handleControlLayers. */
    private final Map<Card, List<String>> cardToControl = new HashMap<>();

    private final Map<Card, String> cardToScript = new HashMap<>();

    private final Map<String, String> abilityString = new HashMap<>();

    private final Set<Card> cardsReferencedByID = new HashSet<>();
    /** The timestamps of the game's static effects as it is written: a control effect with one of them is a static's. */
    private final Set<Long> staticTimestamps = new HashSet<>();
    private final Set<Card> cardsWithoutETBTrigs = new HashSet<>();

    private String tChangePlayer = "NONE";
    private boolean restore = false;
    private boolean deferAdvance = false;
    private PhaseType pendingAdvance = null;
    private String tChangePhase = "NONE";

    private String tAdvancePhase = "NONE";

    private int turn = 1;

    // The game's own pieces that are not cards: who is the monarch (and was, as the turn began), who has
    // the initiative, and day or night. A holder is written "P<n>", with the set its effect card is
    // drawn from after a "|"; null is nobody (day/night: neither).
    private String monarch = null;
    private String monarchBeginTurn = null;
    private String initiative = null;
    private String dayTime = null;

    /**
     * The delayed triggers waiting as the text was written, one {@code delayedtrigger=} line each:
     * {@code <host id>|<activator P<n>>|<delayed, thisturn or P<n> for a player's own turn>|<intrinsic>|
     * <remembered, comma-joined c<card id>, P<n> or i<number>>|<the trigger's parameters, Base64>}.
     */
    private final List<String> delayedTriggers = new ArrayList<>();

    /**
     * The effect cards and emblems the text puts back, in the order the game made them, one
     * {@code effect=} line each: Base64 of {@code key=value} lines naming the ability that made it
     * ({@code host}, a card id; {@code at}, its place in {@link #sourceAbilities}) and what it holds
     * that the ability does not say again (who it remembers, its choices). See {@link #effectLine}.
     */
    private final List<String> effects = new ArrayList<>();
    /** The returns waiting for a duration to end, one {@code return=} line each ({@link #returnLine}). */
    private final List<String> returns = new ArrayList<>();
    /** The effect cards {@link #effects} carries, as the text was written. */
    private final Set<Card> carriedEffects = new HashSet<>();

    /**
     * The managed effect cards this text recreates from its other lines: a commander's from
     * {@code IsCommander}, the monarch's and the initiative's from their own lines, speed's from
     * {@code speed=} ({@code createSpeedEffect}) and radiation's from a player's RAD counters
     * ({@code Player.setCounters}). {@link #unwritten} names every other one. A kind joins this set
     * only with a round trip that proves it.
     */
    private static final Set<String> CARRIED_EFFECTS = Set.of("commander", "monarch", "initiative", "speed", "radiation");

    private boolean removeSummoningSickness = false;

    // Targeting for precast spells in a game state (mostly used by Puzzle Mode game states)
    private final int TARGET_NONE = -1; // untargeted spell (e.g. Joraga Invocation)
    private final int TARGET_HUMAN = -2;
    private final int TARGET_AI = -3;

    public GameState() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (puzzleCreatorState) {
            // append basic puzzle metadata if we're dumping from the puzzle creator screen
            sb.append("[metadata]\n");
            sb.append("Name:New Puzzle\n");
            sb.append("URL:https://www.cardforge.org\n");
            sb.append("Goal:Win\n");
            sb.append("Turns:1\n");
            sb.append("Difficulty:Common\n");
            sb.append("Description:Win this turn.\n");
            sb.append("[state]\n");
        }

        sb.append(TextUtil.concatNoSpace("turn=", String.valueOf(turn), "\n"));
        sb.append(TextUtil.concatNoSpace("activeplayer=", tChangePlayer, "\n"));
        sb.append(TextUtil.concatNoSpace("activephase=", tChangePhase, "\n"));
        if (monarch != null) sb.append(TextUtil.concatNoSpace("monarch=", monarch, "\n"));
        if (monarchBeginTurn != null) sb.append(TextUtil.concatNoSpace("monarchbeginturn=", monarchBeginTurn, "\n"));
        if (initiative != null) sb.append(TextUtil.concatNoSpace("initiative=", initiative, "\n"));
        if (dayTime != null) sb.append(TextUtil.concatNoSpace("daytime=", dayTime, "\n"));
        for (String t : delayedTriggers) sb.append(TextUtil.concatNoSpace("delayedtrigger=", t, "\n"));
        for (String e : effects) sb.append(TextUtil.concatNoSpace("effect=", e, "\n"));
        for (String r : returns) sb.append(TextUtil.concatNoSpace("return=", r, "\n"));

        int playerIndex = 0;
        for (PlayerState p : playerStates) {
            String prefix = "p" + playerIndex++;
            sb.append(TextUtil.concatNoSpace(prefix + "life=", String.valueOf(p.life), "\n"));
            sb.append(TextUtil.concatNoSpace(prefix + "landsplayed=", String.valueOf(p.landsPlayed), "\n"));
            sb.append(TextUtil.concatNoSpace(prefix + "landsplayedlastturn=", String.valueOf(p.landsPlayedLastTurn), "\n"));
            sb.append(TextUtil.concatNoSpace(prefix + "numringtemptedyou=", String.valueOf(p.numRingTemptedYou), "\n"));
            sb.append(TextUtil.concatNoSpace(prefix + "speed=", String.valueOf(p.speed), "\n"));
            if (!p.counters.isEmpty()) {
                sb.append(TextUtil.concatNoSpace(prefix + "counters=", p.counters, "\n"));
            }
            if (!p.manaPool.isEmpty()) {
                sb.append(TextUtil.concatNoSpace(prefix + "manapool=", p.manaPool, "\n"));
            }
            if (!p.persistentMana.isEmpty()) {
                sb.append(TextUtil.concatNoSpace(prefix + "persistentmana=", p.persistentMana, "\n"));
            }
            appendCards(p.cardTexts, prefix, sb);
        }
        return sb.toString();
    }

    private void appendCards(Map<ZoneType, String> cardTexts, String categoryPrefix, StringBuilder sb) {
        for (Entry<ZoneType, String> kv : cardTexts.entrySet()) {
            sb.append(TextUtil.concatNoSpace(categoryPrefix, ZONES.get(kv.getKey()), "=", kv.getValue(), "\n"));
        }
    }

    public void initFromGame(Game game) {
        playerStates.clear();
        for (Player player : game.getPlayers()) {
            PlayerState p = new PlayerState();
            p.life = player.getLife();
            p.landsPlayed = player.getLandsPlayedThisTurn();
            p.landsPlayedLastTurn = player.getLandsPlayedLastTurn();
            p.counters = countersToString(player.getCounters());
            p.manaPool = processManaPool(player.getManaPool());
            p.numRingTemptedYou = player.getNumRingTemptedYou();
            p.speed = player.getSpeed();
            playerStates.add(p);
        }

        tChangePlayer = "p" + game.getPlayers().indexOf(game.getPhaseHandler().getPlayerTurn());
        tChangePhase = game.getPhaseHandler().getPhase().toString();
        turn = game.getPhaseHandler().getTurn();
        monarch = holder(game.getMonarch(), game.getMonarch() == null ? null : game.getMonarch().getMonarchSet());
        monarchBeginTurn = holder(game.getMonarchBeginTurn(), null);
        initiative = holder(game.getHasInitiative(), game.getHasInitiative() == null ? null : game.getHasInitiative().getInitiativeSet());
        // Forge's own sense: getDayTime() true is NIGHT (Game.isNight), false is day.
        dayTime = game.isNeitherDayNorNight() ? null : game.isNight() ? "night" : "day";

        staticTimestamps.clear();
        for (StaticEffect se : game.getStaticEffects().getEffects()) staticTimestamps.add(se.getTimestamp());

        // Mark the cards that need their ID remembered for various reasons
        cardsReferencedByID.clear();
        for (ZoneType zone : ZONES.keySet()) {
            for (Card card : game.getCardsIncludePhasingIn(zone)) {
                if (card.getExiledWith() != null) {
                    // Remember the ID of the card that exiled this card
                    cardsReferencedByID.add(card.getExiledWith());
                }
                if (zone == ZoneType.Battlefield) {
                    if (!card.getAllAttachedCards().isEmpty()) {
                        // Remember the ID of cards that have attachments
                        cardsReferencedByID.add(card);
                    }
                }
                for (Object o : card.getRemembered()) {
                    // Remember the IDs of remembered cards
                    if (o instanceof Card) {
                        cardsReferencedByID.add((Card)o);
                    }
                }
                for (Card i : card.getImprintedCards()) {
                    // Remember the IDs of imprinted cards
                    cardsReferencedByID.add(i);
                }
                for (Card i : card.getChosenCards()) {
                    // Remember the IDs of chosen cards
                    cardsReferencedByID.add(i);
                }
                for (long ts : card.getTempControllers().keySet()) {
                    final Card.ControlCondition until = card.getControlCondition(ts);
                    // Remember the ID of the card a control condition watches
                    if (until != null) cardsReferencedByID.add(until.source());
                }
                if (game.getCombat() != null && game.getCombat().isAttacking(card)) {
                    // Remember the IDs of attacked planeswalkers
                    GameEntity def = game.getCombat().getDefenderByAttacker(card);
                    if (def instanceof Card) {
                        cardsReferencedByID.add((Card)def);
                    }
                }
            }
        }

        // After the marks above are cleared and made: a delayed trigger marks its host and remembered cards too.
        delayedTriggers.clear();
        final TriggerHandler th = game.getTriggerHandler();
        // A this-turn trigger is in both lists (TriggerHandler.registerThisTurnDelayedTrigger): written once, as thisturn.
        for (Trigger t : th.getDelayedTriggers()) if (!th.getThisTurnDelayedTriggers().contains(t)) addDelayedTrigger(t, "delayed");
        for (Trigger t : th.getThisTurnDelayedTriggers()) addDelayedTrigger(t, "thisturn");
        for (Entry<Player, Trigger> e : th.getPlayerDefinedDelayedTriggers().entries()) addDelayedTrigger(e.getValue(), getPlayerString(e.getKey()));
        // The effect cards an ability made, oldest first, so they take their timestamps back in that order.
        effects.clear();
        carriedEffects.clear();
        final List<Card> commanded = new ArrayList<>(game.getCardsIn(ZoneType.Command));
        commanded.sort(Comparator.comparingLong(Card::getGameTimestamp));
        for (Card eff : commanded) {
            final String line = effectLine(eff);
            if (line == null) continue;
            effects.add(line);
            carriedEffects.add(eff);
            cardsReferencedByID.add(game.getCardState(eff.getEffectSourceAbility().getHostCard()));
            for (Object o : eff.getRemembered()) if (o instanceof Card c) cardsReferencedByID.add(c);
            cardsReferencedByID.addAll(eff.getImprintedCards());
            cardsReferencedByID.addAll(eff.getChosenCards());
            markPlaces(game, untilPlaces(eff));
        }
        returns.clear();
        for (Entry<GameCommand, SpellAbilityEffect.ReturnCommand> e : waitingReturns(game).entrySet()) {
            final String line = returnLine(e.getValue(), e.getKey());
            if (line == null) continue;
            returns.add(line);
            final Map<String, String> f = fields(line);
            cardsReferencedByID.add(e.getValue().hostCard());
            for (String cell : ids(f.get("moved"))) {
                for (String id : cell.split(">", 3)[2].split("/")) cardsReferencedByID.add(game.findById(Integer.parseInt(id)));
            }
            markPlaces(game, ids(f.get("until")));
        }
        for (ZoneType zone : ZONES.keySet()) {
            // Init texts to empty, so that restoring will clear the state
            // if the zone had no cards in it (e.g. empty hand).
            for (PlayerState p : playerStates) {
                p.cardTexts.put(zone, "");
            }
            for (Card card : game.getCardsIncludePhasingIn(zone)) {
                if (card.getName().equals("Puzzle Goal") && card.getOracleText().contains("New Puzzle")) {
                    puzzleCreatorState = true;
                }
                if (card instanceof DetachedCardEffect) {
                    continue;
                }
                int playerIndex = game.getPlayers().indexOf(card.getZone().getPlayer());
                addCard(zone, playerStates.get(playerIndex).cardTexts, card);
            }
        }
    }

    /** One delayed trigger as its {@code delayedtrigger=} line, its host and remembered cards marked to keep their ids; nothing when it cannot be written. */
    private void addDelayedTrigger(final Trigger t, final String kind) {
        final String line = delayedTriggerLine(t, kind);
        if (line == null) return;
        delayedTriggers.add(line);
        cardsReferencedByID.add(t.getHostCard());
        for (Object o : t.getTriggerRemembered()) if (o instanceof Card) cardsReferencedByID.add((Card) o);
    }

    /**
     * A delayed trigger as text, or null when it cannot be: its host must be in a zone this text writes,
     * have a paper card, and hold the SVar the trigger executes; what it remembers must be cards in
     * those zones, players or numbers.
     */
    private static String delayedTriggerLine(final Trigger t, final String kind) {
        final Card host = t.getHostCard();
        if (host == null || host.getZone() == null || !ZONES.containsKey(host.getZone().getZoneType())) return null;
        if (host.isToken() || host.getPaperCard() == null) return null;
        final String execute = t.getParam("Execute");
        if (execute != null && !host.hasSVar(execute)) return null;
        final List<String> remembered = new ArrayList<>();
        for (Object o : t.getTriggerRemembered()) {
            if (o instanceof Card c && c.getZone() != null && ZONES.containsKey(c.getZone().getZoneType()) && !c.isToken() && c.getPaperCard() != null) remembered.add("c" + c.getId());
            else if (o instanceof Player p) remembered.add("P" + p.getGame().getPlayers().indexOf(p));
            else if (o instanceof Integer i) remembered.add("i" + i);
            else return null;
        }
        final Player activator = t.getSpawningAbility() != null && t.getSpawningAbility().getActivatingPlayer() != null
                ? t.getSpawningAbility().getActivatingPlayer() : host.getController();
        final StringBuilder params = new StringBuilder();
        for (Entry<String, String> e : t.getMapParams().entrySet()) {
            if (params.length() > 0) params.append(" | ");
            params.append(e.getKey()).append("$ ").append(e.getValue());
        }
        return host.getId() + "|P" + host.getGame().getPlayers().indexOf(activator) + "|" + kind + "|" + t.isIntrinsic() + "|"
                + String.join(",", remembered) + "|" + Base64.getEncoder().encodeToString(params.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Register every {@code delayedtrigger=} line again, on the cards this text just made. */
    private void handleDelayedTriggers(final Game game) {
        final TriggerHandler th = game.getTriggerHandler();
        if (restore) {
            th.clearDelayedTrigger();
            th.clearThisTurnDelayedTrigger();
            th.clearPlayerDefinedDelayedTrigger();
        }
        for (String line : delayedTriggers) {
            final String[] f = line.split("\\|", -1);
            final Card host = idToCard.get(Integer.parseInt(f[0]));
            if (host == null) {
                System.err.println("ERROR: no card with id " + f[0] + " to host a delayed trigger");
                continue;
            }
            final Player activator = parsePlayerString(game, f[1]);
            final String params = new String(Base64.getDecoder().decode(f[5]), java.nio.charset.StandardCharsets.UTF_8);
            final Trigger t = TriggerHandler.parseTrigger(params, host, Boolean.parseBoolean(f[3]));
            final SpellAbility spawn = AbilityFactory.getAbility("DB$ DelayedTrigger | " + params, host);
            spawn.setActivatingPlayer(activator);
            t.setSpawningAbility(spawn);
            t.setActiveZone(null);
            if (!f[4].isEmpty()) {
                for (String r : f[4].split(",")) {
                    if (r.startsWith("c")) t.addRemembered(idToCard.get(Integer.parseInt(r.substring(1))));
                    else if (r.startsWith("P")) t.addRemembered(parsePlayerString(game, r));
                    else if (r.startsWith("i")) t.addRemembered(Integer.parseInt(r.substring(1)));
                }
            }
            if (t.hasParam("Execute")) {
                final SpellAbility exec = AbilityFactory.getAbility(host.getSVar(t.getParam("Execute")), host);
                exec.setActivatingPlayer(activator);
                t.setOverridingAbility(exec);
            }
            if (f[2].equals("thisturn")) th.registerThisTurnDelayedTrigger(t);
            else if (f[2].equals("delayed")) th.registerDelayedTrigger(t);
            else th.registerPlayerDefinedDelayedTrigger(parsePlayerString(game, f[2]), t);
        }
    }

    /** Keep the ids of the cards a command waits on ({@code card:<id>:<list>}). */
    private void markPlaces(final Game game, final List<String> places) {
        for (String place : places) {
            if (place.startsWith("card:")) cardsReferencedByID.add(game.findById(Integer.parseInt(place.split(":")[1])));
        }
    }

    /** Whether an adventure effect the text does not carry remembers {@code c}: "on an adventure" says it again. */
    private boolean onUncarriedAdventure(final Card c) {
        for (Card eff : c.getGame().getCardsIn(ZoneType.Command)) {
            final SpellAbility sa = eff.getEffectSourceAbility();
            if (sa != null && sa.hasParam("Adventure") && eff.isRemembered(c) && !carriedEffects.contains(eff)) return true;
        }
        return false;
    }

    /**
     * Every ability {@code host} has that can make an effect card, in an order a card made from the same
     * text has too: each state's spell abilities, its triggers' and its replacements' abilities, each
     * with the abilities under it.
     */
    private static List<SpellAbility> sourceAbilities(final Card host) {
        final List<SpellAbility> out = new ArrayList<>();
        for (CardStateName name : new TreeSet<>(host.getStates())) {
            final CardState state = host.getState(name);
            for (SpellAbility sa : state.getSpellAbilities()) addAbilityTree(sa, out);
            for (Trigger t : state.getTriggers()) addAbilityTree(t.ensureAbility(), out);
            for (ReplacementEffect re : state.getReplacementEffects()) addAbilityTree(re.getOverridingAbility(), out);
        }
        return out;
    }

    private static void addAbilityTree(final SpellAbility sa, final List<SpellAbility> out) {
        if (sa == null || out.contains(sa)) return;
        out.add(sa);
        addAbilityTree(sa.getSubAbility(), out);
        for (SpellAbility a : sa.getAdditionalAbilities().values()) addAbilityTree(a, out);
        for (List<AbilitySub> l : sa.getAdditionalAbilityLists().values()) for (AbilitySub a : l) addAbilityTree(a, out);
    }

    /** A card the text writes with its id: in a written zone as itself, and a token or a card with a paper card. */
    private static boolean writtenWithId(final Card c) {
        return c != null && c.getZone() != null && ZONES.containsKey(c.getZone().getZoneType()) && c.getZone().contains(c)
                && !(c instanceof DetachedCardEffect) && (c.isToken() || c.getPaperCard() != null);
    }

    /**
     * An effect card as its {@code effect=} line, or null when the line would not make it again as it is.
     * Carried: a card an Effect ability made, whose host is a written card holding that ability; rebuilt
     * by {@link EffectEffect#buildEffect} from it. One lasting the game (an emblem, a boon, an adventure)
     * needs nothing more; one with a duration needs its end found ({@link #untilPlaces}). Not carried:
     * one that changed since it was made (its SVars, counters or text), one its host imprints, and one
     * remembering what the text cannot name (a spell, a last-known copy).
     */
    private static String effectLine(final Card eff) {
        if (eff.isToken() || eff.getPaperCard() != null || eff.getGamePieceType() != GamePieceType.EFFECT) return null;
        final SpellAbility sa = eff.getEffectSourceAbility();
        if (sa == null || sa.getApi() != ApiType.Effect || sa.hasParam("ImprintOnHost")) return null;
        final Game game = eff.getGame();
        final List<String> until = untilPlaces(eff);
        if (until == null) return null;
        final Card host = game.getCardState(sa.getHostCard(), null);
        if (!writtenWithId(host)) return null;
        final List<SpellAbility> abilities = sourceAbilities(host);
        int at = -1;
        for (int i = 0; i < abilities.size() && at < 0; i++) {
            final SpellAbility a = abilities.get(i);
            if (a.getApi() == ApiType.Effect && a.getMapParams().equals(sa.getMapParams()) && a.getSVars().equals(sa.getSVars())) at = i;
        }
        if (at < 0) return null;
        if (!eff.getSVars().equals(sa.getSVars()) || !eff.getCounters().isEmpty()
                || !eff.getChangedTextColorWords().isEmpty() || !eff.getChangedTextTypeWords().isEmpty()) return null;
        final List<String> remembered = new ArrayList<>();
        for (Object o : eff.getRemembered()) {
            if (o instanceof Card c && writtenWithId(c)) remembered.add("c" + c.getId());
            else if (o instanceof Player p) remembered.add("P" + game.getPlayers().indexOf(p));
            else if (o instanceof Integer i) remembered.add("i" + i);
            else if (o instanceof String str) remembered.add("s" + base64(str));
            else return null;
        }
        final List<String> imprinted = new ArrayList<>();
        for (Card c : eff.getImprintedCards()) {
            if (!writtenWithId(c)) return null;
            imprinted.add(String.valueOf(c.getId()));
        }
        final List<String> chosenCards = new ArrayList<>();
        for (Card c : eff.getChosenCards()) {
            if (!writtenWithId(c)) return null;
            chosenCards.add(String.valueOf(c.getId()));
        }
        final List<String> named = new ArrayList<>();
        for (String n : eff.getNamedCards()) named.add(base64(n));
        final StringBuilder f = new StringBuilder();
        f.append("host=").append(host.getId()).append('\n');
        f.append("at=").append(at).append('\n');
        f.append("owner=P").append(game.getPlayers().indexOf(eff.getOwner())).append('\n');
        if (sa.getActivatingPlayer() != null) f.append("activator=P").append(game.getPlayers().indexOf(sa.getActivatingPlayer())).append('\n');
        f.append("name=").append(eff.getName()).append('\n');
        f.append("until=").append(String.join(",", until)).append('\n');
        if (eff.getImageKey() != null) f.append("image=").append(eff.getImageKey()).append('\n');
        f.append("remembered=").append(String.join(",", remembered)).append('\n');
        f.append("imprinted=").append(String.join(",", imprinted)).append('\n');
        if (eff.hasChosenColor()) f.append("colors=").append(String.join(",", eff.getChosenColors())).append('\n');
        f.append("chosencards=").append(String.join(",", chosenCards)).append('\n');
        if (eff.hasChosenPlayer()) f.append("chosenplayer=P").append(game.getPlayers().indexOf(eff.getChosenPlayer())).append('\n');
        if (eff.getChosenDirection() != null) f.append("direction=").append(eff.getChosenDirection().name()).append('\n');
        if (eff.hasChosenType()) f.append("type=").append(eff.getChosenType()).append('\n');
        if (eff.hasChosenType2()) f.append("type2=").append(eff.getChosenType2()).append('\n');
        f.append("named=").append(String.join(",", named)).append('\n');
        if (eff.hasChosenNumber()) f.append("number=").append(eff.getChosenNumber()).append('\n');
        return base64(f.toString());
    }

    /** The game's phases whose commands a turn-start text writes, by the name it writes them under. */
    private static Map<String, forge.game.phase.Phase> phases(final Game game) {
        final Map<String, forge.game.phase.Phase> phases = new LinkedHashMap<>();
        phases.put("untap", game.getUntap());
        phases.put("upkeep", game.getUpkeep());
        phases.put("beginofcombat", game.getBeginOfCombat());
        phases.put("endofcombat", game.getEndOfCombat());
        phases.put("endofturn", game.getEndOfTurn());
        phases.put("cleanup", game.getCleanup());
        return phases;
    }

    /**
     * Where the command that ends {@code eff} waits: {@code phase:<phase>:<place>} ({@link
     * forge.game.phase.Phase#placesOf}) and {@code card:<id>:<list>} ({@link Card#commandPlacesOf}),
     * empty for an effect lasting the game, null when its end cannot be written: it waits somewhere
     * else (a state-based-action check), on a card the text does not write, or nowhere this finds.
     */
    private static List<String> untilPlaces(final Card eff) {
        final SpellAbility sa = eff.getEffectSourceAbility();
        final boolean lasting = "Permanent".equals(sa.getParam("Duration"));
        final Game game = eff.getGame();
        final List<String> places = commandPlaces(game, c -> unwrapped(c) instanceof SpellAbilityEffect.ExileEffectCommand x && x.effect() == eff);
        return places != null && lasting == places.isEmpty() ? places : null;
    }

    /**
     * Where the commands {@code test} picks wait: {@code phase:<phase>:<place>} ({@link
     * forge.game.phase.Phase#placesOf}) and {@code card:<id>:<list>} ({@link Card#commandPlacesOf}); null
     * when one waits where the text cannot say (a state-based-action check, a card it does not write).
     */
    private static List<String> commandPlaces(final Game game, final java.util.function.Predicate<GameCommand> test) {
        if (game.sbaCheckedCommandList.stream().anyMatch(test)) return null;
        final List<String> places = new ArrayList<>();
        for (Entry<String, forge.game.phase.Phase> e : phases(game).entrySet()) {
            for (String place : e.getValue().placesOf(test, game.getPlayers())) places.add("phase:" + e.getKey() + ":" + place);
        }
        for (Card c : game.getCardsInGame()) {
            final List<String> lists = c.commandPlacesOf(test);
            if (lists.isEmpty()) continue;
            if (!writtenWithId(c)) return null;
            for (String list : lists) places.add("card:" + c.getId() + ":" + list);
        }
        return places;
    }

    /** Put {@code command} back in every place {@link #commandPlaces} wrote. */
    private void putCommand(final Game game, final String places, final GameCommand command) {
        for (String place : ids(places)) {
            final String[] p = place.split(":", 3);
            if (p[0].equals("phase")) phases(game).get(p[1]).putAt(p[2], game.getPlayers(), command);
            else idToCard.get(Integer.parseInt(p[1])).addCommand(p[2], command);
        }
    }

    /** Every command waiting in the game, in a phase, on a card or for a state-based-action check. */
    private static List<GameCommand> waitingCommands(final Game game) {
        final List<GameCommand> waiting = new ArrayList<>(game.sbaCheckedCommandList);
        for (forge.game.phase.Phase phase : phases(game).values()) waiting.addAll(phase.commands());
        for (Card c : game.getCardsInGame()) waiting.addAll(c.commands());
        return waiting;
    }

    /**
     * A return waiting for its duration to end ({@link SpellAbilityEffect.ReturnCommand}: Banisher Priest's
     * "until it leaves the battlefield") as its {@code return=} line, or null when the line would not put
     * it back as it is: its host must be a written card holding the ability, every card still waiting to
     * return a written card, and its ability without a {@code ReturnAbility} (whose trigger is made from
     * the host as it was then, which the text does not hold). {@code wrapper} is the command as it waits.
     */
    private static String returnLine(final SpellAbilityEffect.ReturnCommand r, final GameCommand wrapper) {
        final Card host = r.hostCard();
        final Game game = host.getGame();
        if (!writtenWithId(host) || game.getCardState(host, null) != host || r.sa().hasParam("ReturnAbility")) return null;
        final List<SpellAbility> abilities = sourceAbilities(host);
        int at = -1;
        for (int i = 0; i < abilities.size() && at < 0; i++) {
            final SpellAbility a = abilities.get(i);
            if (a.getApi() == r.sa().getApi() && a.getMapParams().equals(r.sa().getMapParams())) at = i;
        }
        if (at < 0) return null;
        final List<String> moved = new ArrayList<>();
        for (com.google.common.collect.Table.Cell<ZoneType, ZoneType, CardCollection> cell : r.moved().cellSet()) {
            final List<String> waiting = new ArrayList<>();
            for (Card c : cell.getValue()) {
                if (!host.getUntilLeavesBattlefield().contains(c) || game.getCardState(c, null) != c) continue;
                if (!writtenWithId(c)) return null;
                waiting.add(String.valueOf(c.getId()));
            }
            if (!waiting.isEmpty()) moved.add(cell.getRowKey() + ">" + cell.getColumnKey() + ">" + String.join("/", waiting));
        }
        final List<String> places = commandPlaces(game, c -> c == wrapper);
        if (places == null || places.isEmpty()) return null;
        final StringBuilder f = new StringBuilder();
        f.append("host=").append(host.getId()).append('\n');
        f.append("at=").append(at).append('\n');
        if (r.sa().getActivatingPlayer() != null) f.append("activator=P").append(game.getPlayers().indexOf(r.sa().getActivatingPlayer())).append('\n');
        f.append("moved=").append(String.join(",", moved)).append('\n');
        f.append("until=").append(String.join(",", places)).append('\n');
        return base64(f.toString());
    }

    /** The returns waiting in the game, each once, as the command that waits and the return it runs. */
    private static Map<GameCommand, SpellAbilityEffect.ReturnCommand> waitingReturns(final Game game) {
        final Map<GameCommand, SpellAbilityEffect.ReturnCommand> returns = new IdentityHashMap<>();
        for (GameCommand c : waitingCommands(game)) {
            if (c instanceof SpellAbilityEffect.UntilCommand u && u.spent()) continue;
            if (unwrapped(c) instanceof SpellAbilityEffect.ReturnCommand r) returns.put(c, r);
        }
        return returns;
    }

    /** Make every {@code return=} line's return again, on the cards this text just made, and put it where it waited. */
    private void handleReturns(final Game game) {
        for (String line : returns) {
            final Map<String, String> f = fields(line);
            final Card host = idToCard.get(Integer.parseInt(f.get("host")));
            final List<SpellAbility> abilities = host == null ? List.of() : sourceAbilities(host);
            final int at = Integer.parseInt(f.get("at"));
            if (at >= abilities.size()) {
                System.err.println("ERROR: no ability " + at + " on card " + f.get("host") + " to return what it moved");
                continue;
            }
            final SpellAbility sa = abilities.get(at);
            if (f.containsKey("activator")) sa.setActivatingPlayer(parsePlayerString(game, f.get("activator")));
            final CardZoneTable moved = new CardZoneTable();
            for (String cell : ids(f.get("moved"))) {
                final String[] p = cell.split(">", 3);
                final CardCollection cards = new CardCollection();
                for (String id : p[2].split("/")) cards.add(idToCard.get(Integer.parseInt(id)));
                moved.put(ZoneType.smartValueOf(p[0]), ZoneType.smartValueOf(p[1]), cards);
                host.addUntilLeavesBattlefield(cards);
            }
            putCommand(game, f.get("until"), SpellAbilityEffect.untilCommand(new SpellAbilityEffect.ReturnCommand(sa, host, moved, null), sa.getApi().name()));
        }
    }

    private static Map<String, String> fields(final String line) {
        final Map<String, String> f = new HashMap<>();
        for (String kv : unbase64(line).split("\n")) {
            final int eq = kv.indexOf('=');
            if (eq > 0) f.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return f;
    }

    private static GameCommand unwrapped(final GameCommand c) {
        return c instanceof SpellAbilityEffect.UntilCommand u ? u.command() : c;
    }

    /**
     * The commands waiting in the game that this text does not write, each as {@code command:<origin>}:
     * the ApiType of the ability that made it, or the class that registered it. An effect card's end
     * and a control's end are not among them (an effect card or a control this text does not carry is
     * named as one already), nor a return a {@code return=} line writes, nor a duration's end that has run
     * from another place it waited.
     */
    private static Set<String> unwrittenCommands(final Game game) {
        final Set<String> kinds = new TreeSet<>();
        for (GameCommand c : waitingCommands(game)) {
            if (c instanceof SpellAbilityEffect.UntilCommand u && u.spent()) continue;
            final GameCommand inner = unwrapped(c);
            if (inner instanceof SpellAbilityEffect.ExileEffectCommand || inner instanceof forge.game.ability.effects.ControlGainEffect.LoseControlCommand) continue;
            if (inner instanceof SpellAbilityEffect.ReturnCommand r && returnLine(r, c) != null) continue;
            kinds.add("command:" + (c instanceof SpellAbilityEffect.UntilCommand u ? u.origin() : registrar(c)));
        }
        return kinds;
    }

    /** The class a command was written in, without its package or its anonymous and lambda suffixes. */
    private static String registrar(final GameCommand c) {
        final String name = c.getClass().getName();
        final String simple = name.substring(name.lastIndexOf('.') + 1);
        final int dollar = simple.indexOf('$');
        return dollar < 0 ? simple : simple.substring(0, dollar);
    }

    private static String base64(final String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String unbase64(final String s) {
        return new String(Base64.getDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Make every {@code effect=} line's card again, from its ability on the card this text just made, and put it in its owner's command zone. */
    private void handleEffects(final Game game) {
        for (String line : effects) {
            final Map<String, String> f = fields(line);
            final Card host = idToCard.get(Integer.parseInt(f.get("host")));
            final List<SpellAbility> abilities = host == null ? List.of() : sourceAbilities(host);
            final int at = Integer.parseInt(f.get("at"));
            if (at >= abilities.size()) {
                System.err.println("ERROR: no ability " + at + " on card " + f.get("host") + " to make " + f.get("name"));
                continue;
            }
            final SpellAbility sa = abilities.get(at);
            if (f.containsKey("activator")) sa.setActivatingPlayer(parsePlayerString(game, f.get("activator")));
            final Player owner = parsePlayerString(game, f.get("owner"));
            final boolean remembers = sa.hasParam("RememberSpell") || sa.hasParam("RememberObjects") || sa.hasParam("RememberLKI");
            final CardCollection imprinted = new CardCollection();
            for (String id : ids(f.get("imprinted"))) imprinted.add(idToCard.get(Integer.parseInt(id)));
            final Card eff = EffectEffect.buildEffect(sa, owner, f.get("name"), f.get("image"),
                    remembers ? new forge.util.collect.FCollection<>() : null, imprinted.isEmpty() ? null : imprinted, true);
            for (String r : ids(f.get("remembered"))) {
                final String v = r.substring(1);
                switch (r.charAt(0)) {
                    case 'c' -> eff.addRemembered(idToCard.get(Integer.parseInt(v)));
                    case 'P' -> eff.addRemembered(parsePlayerString(game, r));
                    case 'i' -> eff.addRemembered(Integer.parseInt(v));
                    default -> eff.addRemembered(unbase64(v));
                }
            }
            if (f.containsKey("colors")) eff.setChosenColors(Arrays.asList(f.get("colors").split(",")));
            final CardCollection chosen = new CardCollection();
            for (String id : ids(f.get("chosencards"))) chosen.add(idToCard.get(Integer.parseInt(id)));
            if (!chosen.isEmpty()) eff.setChosenCards(chosen);
            if (f.containsKey("chosenplayer")) eff.setChosenPlayer(parsePlayerString(game, f.get("chosenplayer")));
            if (f.containsKey("direction")) eff.setChosenDirection(Direction.valueOf(f.get("direction")));
            if (f.containsKey("type")) eff.setChosenType(f.get("type"));
            if (f.containsKey("type2")) eff.setChosenType2(f.get("type2"));
            final List<String> named = new ArrayList<>();
            for (String n : ids(f.get("named"))) named.add(unbase64(n));
            if (!named.isEmpty()) eff.setNamedCards(named);
            if (f.containsKey("number")) eff.setChosenNumber(Integer.parseInt(f.get("number")));
            putCommand(game, f.get("until"), SpellAbilityEffect.untilCommand(new SpellAbilityEffect.ExileEffectCommand(eff), ApiType.Effect.name()));
            eff.updateStateForView();
            owner.getZone(ZoneType.Command).add(eff);
            owner.updateZoneForView(owner.getZone(ZoneType.Command));
        }
    }

    private static List<String> ids(final String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }

    private String holder(final Player p, final String set) {
        if (p == null) return null;
        return set == null ? getPlayerString(p) : getPlayerString(p) + "|" + set;
    }

    /**
     * What {@link #initFromGame} cannot write of {@code game}, by kind: a turn-start text of a game
     * holding any of it does not put that game back. The card kinds are this class's own skips — a
     * {@link DetachedCardEffect}, and a card that is not a token and has no paper card (an emblem, an
     * effect) — with the managed effect cards it does recreate ({@link #CARRIED_EFFECTS}) left out
     * and the others named ({@code effect:keyword}). Control is written in its layers; what is not is
     * a control effect that ends on a condition ({@code control-condition}: "for as long as", "until
     * it untaps"), whose condition lives in an anonymous command. Then every other command waiting to
     * end something, in a phase or on a card ({@link #unwrittenCommands}: {@code command:<origin>}),
     * which no line writes. Empty is the whole game.
     */
    public static List<String> unwritten(final Game game) {
        final Set<String> kinds = new TreeSet<>();
        for (ZoneType zone : ZONES.keySet()) {
            for (Card c : game.getCardsIncludePhasingIn(zone)) {
                final String managed = c.getOwner() == null ? null : c.getOwner().managedEffectName(c);
                if (managed != null) {
                    // The keyword card of a player whose keywords are all statics' is made again by those statics.
                    final boolean byStatics = managed.equals("keyword") && !c.getOwner().hasResolvedKeywords();
                    if (!CARRIED_EFFECTS.contains(managed) && !byStatics) kinds.add("effect:" + managed);
                } else if (c instanceof DetachedCardEffect) {
                    kinds.add("detached-effect");
                } else if (!c.isToken() && c.getPaperCard() == null && effectLine(c) == null) {
                    kinds.add(c.isEmblem() ? "emblem" : "effect");
                }
                if (zone == ZoneType.Battlefield) {
                    for (long ts : c.getTempControllers().keySet()) {
                        final Card.ControlCondition until = c.getControlCondition(ts);
                        if (until != null && !forge.game.ability.effects.ControlGainEffect.CARRIED_CONDITIONS.containsAll(until.kinds())) kinds.add("control-condition");
                    }
                }
            }
        }
        final TriggerHandler th = game.getTriggerHandler();
        for (Trigger t : th.getDelayedTriggers()) if (!th.getThisTurnDelayedTriggers().contains(t) && delayedTriggerLine(t, "delayed") == null) kinds.add("delayed-trigger");
        for (Trigger t : th.getThisTurnDelayedTriggers()) if (delayedTriggerLine(t, "thisturn") == null) kinds.add("delayed-trigger");
        for (Trigger t : th.getPlayerDefinedDelayedTriggers().values()) if (delayedTriggerLine(t, "P0") == null) kinds.add("delayed-trigger");
        kinds.addAll(unwrittenCommands(game));
        return new ArrayList<>(kinds);
    }

    private String getPlayerString(Player p) {
        return "P" + p.getGame().getPlayers().indexOf(p);
    }

    private Player parsePlayerString(Game game, String str) {
        if (str.equalsIgnoreCase("HUMAN")) {
            return game.getPlayers().get(0);
        } else if (str.equalsIgnoreCase("AI")) {
            return game.getPlayers().get(1);
        } else if (str.startsWith("P") && Character.isDigit(str.charAt(1))) {
            return game.getPlayers().get(Integer.parseInt(String.valueOf(str.charAt(1))));
        } else {
            return game.getPlayers().get(0);
        }
    }

    private void addCard(ZoneType zoneType, Map<ZoneType, String> cardTexts, Card c) {
        StringBuilder newText = new StringBuilder(cardTexts.get(zoneType));
        if (newText.length() > 0) {
            newText.append(";");
        }
        if (c.isToken()) {
            // A token's image is `name|SET|n|n`, and `|` separates this text's fields: written with `/`, read back.
            String token = new TokenInfo(c).toString();
            int image = token.lastIndexOf("Image:");
            if (image >= 0) token = token.substring(0, image) + token.substring(image).replace('|', '/');
            newText.append("t:").append(token);
        } else {
            if (c.getPaperCard() == null) {
                return;
            }

            if (c.hasMergedCard()) {
                String suffix = c.getTopMergedCard().hasPaperFoil() ? "+" : "";
                // we have to go by the current top card name here
                newText.append(c.getTopMergedCard().getPaperCard().getName()).append(suffix).append("|Set:")
                        .append(c.getTopMergedCard().getPaperCard().getEdition()).append("|Art:")
                        .append(c.getTopMergedCard().getPaperCard().getArtIndex());
            } else {
                String suffix = c.hasPaperFoil() ? "+" : "";
                newText.append(c.getPaperCard().getName()).append(suffix).append("|Set:").append(c.getPaperCard().getEdition())
                        .append("|Art:").append(c.getPaperCard().getArtIndex());
            }
        }
        if (c.isCommander()) {
            newText.append("|IsCommander");
        }
        if (c.isRingBearer()) {
            newText.append("|IsRingBearer");
        }

        if (cardsReferencedByID.contains(c)) {
            newText.append("|Id:").append(c.getId());
        }

        if (zoneType == ZoneType.Battlefield) {
            if (c.getOwner() != c.getController()) {
                newText.append("|Owner:").append(getPlayerString(c.getOwner()));
            }
            // Control in its layers: the base controller, then each control a resolved effect gave, in
            // timestamp order. A static ability's (an Aura's) is not written: statics give it again.
            // A control that ends on its source's leaving, untapping or changing hands follows its entry as
            // ControlUntil:<kinds joined by +>@<source id>.
            final List<String> gained = new ArrayList<>();
            for (Entry<Long, Player> e : c.getTempControllers().entrySet()) {
                if (staticTimestamps.contains(e.getKey())) continue;
                String entry = "|GainedControl:" + getPlayerString(e.getValue());
                final Card.ControlCondition until = c.getControlCondition(e.getKey());
                if (until != null) entry += "|ControlUntil:" + String.join("+", until.kinds()) + "@" + until.source().getId();
                gained.add(entry);
            }
            if (c.getBaseController() != c.getController() || !gained.isEmpty()) {
                newText.append("|BaseController:").append(getPlayerString(c.getBaseController()));
                for (String entry : gained) newText.append(entry);
            }
            if (c.isTapped()) {
                newText.append("|Tapped");
            }
            if (c.isSick()) {
                newText.append("|SummonSick");
            }
            if (c.isRenowned()) {
                newText.append("|Renowned");
            }
            if (c.isSolved()) {
                newText.append("|Solved");
            }
            if (c.isSuspected()) {
                newText.append("|Suspected");
            }
            if (c.isMonstrous()) {
                newText.append("|Monstrous");
            }
            if (c.isPhasedOut()) {
                newText.append("|PhasedOut:");
                newText.append(getPlayerString(c.getPhasedOut()));
            }
            if (c.isFaceDown()) {
                newText.append("|FaceDown");
                if (c.isManifested()) {
                    newText.append(":Manifested");
                }
                if (c.isCloaked()) {
                    newText.append(":Cloaked");
                }
            }
            if (c.getCurrentStateName().equals(CardStateName.Flipped)) {
                newText.append("|Flipped");
            } else if (c.getCurrentStateName().equals(CardStateName.Meld)) {
                newText.append("|Meld");
                if (c.getMeldedWith() != null) {
                    String suffix = c.getMeldedWith().hasPaperFoil() ? "+" : "";
                    newText.append(":");
                    newText.append(c.getMeldedWith().getName()).append(suffix);
                }
            } else if (c.getCurrentStateName().equals(CardStateName.Backside)) {
                if (c.isModal()) {
                    newText.append("|Modal");
                } else {
                    newText.append("|Transformed");
                }
            }

            if (c.getPlayerAttachedTo() != null) {
                newText.append("|EnchantingPlayer:");
                newText.append(getPlayerString(c.getPlayerAttachedTo()));
            } else if (c.isAttachedToEntity()) {
                newText.append("|AttachedTo:").append(c.getEntityAttachedTo().getId());
            }

            if (c.getDamage() > 0) {
                newText.append("|Damage:").append(c.getDamage());
            }

            if (c.hasChosenColor()) {
                newText.append("|ChosenColor:").append(TextUtil.join(c.getChosenColors(), ","));
            }
            if (c.hasChosenType()) {
                newText.append("|ChosenType:").append(c.getChosenType());
            }
            if (c.hasChosenType2()) {
                newText.append("|ChosenType2:").append(c.getChosenType2());
            }
            if (!c.getNamedCard().isEmpty()) {
                newText.append("|NamedCard:").append(c.getNamedCard());
            }

            List<String> chosenCardIds = Lists.newArrayList();
            for (Card obj : c.getChosenCards()) {
                chosenCardIds.add(String.valueOf(obj.getId()));
            }
            if (!chosenCardIds.isEmpty()) {
                newText.append("|ChosenCards:").append(TextUtil.join(chosenCardIds, ","));
            }

            List<String> rememberedCardIds = Lists.newArrayList();
            for (Object obj : c.getRemembered()) {
                if (obj instanceof Card) {
                    int id = ((Card)obj).getId();
                    rememberedCardIds.add(String.valueOf(id));
                }
            }
            if (!rememberedCardIds.isEmpty()) {
                newText.append("|RememberedCards:").append(TextUtil.join(rememberedCardIds, ","));
            }

            List<String> imprintedCardIds = Lists.newArrayList();
            for (Card impr : c.getImprintedCards()) {
                int id = impr.getId();
                imprintedCardIds.add(String.valueOf(id));
            }
            if (!imprintedCardIds.isEmpty()) {
                newText.append("|Imprinting:").append(TextUtil.join(imprintedCardIds, ","));
            }

            if (c.hasMergedCard()) {
                List<String> mergedCardNames = new ArrayList<>();
                for (Card merged : c.getMergedCards()) {
                    if (c.getTopMergedCard() == merged) {
                        continue;
                    }
                    mergedCardNames.add(merged.getPaperCard().getName().replace(",", "^"));
                }
                newText.append("|MergedCards:").append(TextUtil.join(mergedCardNames, ","));
            }

            if (c.getClassLevel() > 1) {
                newText.append("|ClassLevel:").append(c.getClassLevel());
            }
        }

        if (zoneType == ZoneType.Exile) {
            if (c.getExiledWith() != null) {
                newText.append("|ExiledWith:").append(c.getExiledWith().getId());
            }
            if (c.isFaceDown()) {
                newText.append("|FaceDown"); // Exiled face down
            }
            if (c.isAdventureCard() && c.getZone().is(ZoneType.Exile) && onUncarriedAdventure(c)) {
                newText.append("|OnAdventure");
            }
            if (c.isForetold()) {
                newText.append("|Foretold");
                if (c.enteredThisTurn()) {
                    newText.append("|ForetoldThisTurn");
                }
            }
        }

        if (zoneType == ZoneType.Battlefield || zoneType == ZoneType.Exile) {
            // A card can have counters on the battlefield and in exile (e.g. exiled by Mairsil, the Pretender)
            if (!c.getCounters().isEmpty()) {
                newText.append("|Counters:");
                newText.append(countersToString(c.getCounters()));
            }
        }

        if (c.getGame().getCombat() != null) {
            if (c.getGame().getCombat().isAttacking(c)) {
                newText.append("|Attacking");
                GameEntity def = c.getGame().getCombat().getDefenderByAttacker(c);
                if (def instanceof Card) {
                    newText.append(":").append(def.getId());
                }
            }
        }

        if (!c.getUnlockedRooms().isEmpty()) {
            for (CardStateName stateName : c.getUnlockedRooms()) {
                newText.append("|UnlockedRoom:");
                newText.append(stateName.name());
            }
        }

        cardTexts.put(zoneType, newText.toString());
    }

    private String countersToString(Multiset<CounterType> counters) {
        boolean first = true;
        StringBuilder counterString = new StringBuilder();

        for (Multiset.Entry<CounterType> kv : counters.entrySet()) {
            if (!first) {
                counterString.append(",");
            }

            first = false;
            counterString.append(TextUtil.concatNoSpace(kv.getElement().toString(), "=", String.valueOf(kv.getCount())));
        }
        return counterString.toString();
    }

    private String[] splitLine(String line) {
        if (line.charAt(0) == '#') {
            return null;
        }
        final String[] tempData = line.split("=", 2);
        if (tempData.length >= 2) {
            return tempData;
        }
        if (tempData.length == 1 && line.endsWith("=")) {
            // Empty value.
            return new String[] {tempData[0], ""};
        }
        return null;
    }

    public void parse(InputStream in) throws Exception {
        final BufferedReader br = new BufferedReader(new InputStreamReader(in));
        parse(br.lines());
    }

    public void parse(List<String> lines) {
        parse(lines.stream());
    }

    public void parse(Stream<String> lines) {
        playerStates.clear();
        delayedTriggers.clear();
        effects.clear();
        returns.clear();
        lines.forEach(this::parseLine);
    }


    private PlayerState getPlayerState(int index) {
        while (index >= playerStates.size()) {
            playerStates.add(new PlayerState());
        }
        return playerStates.get(index);
    }

    private PlayerState getPlayerState(String key) {
        if (key.startsWith("human")) {
            return getPlayerState(0);
        } else if (key.startsWith("ai")) {
            return getPlayerState(1);
        } else if (key.startsWith("p") && Character.isDigit(key.charAt(1))) {
            return getPlayerState(Integer.parseInt(String.valueOf(key.charAt(1))));
        } else {
            System.err.println("Unknown player state key: " + key);
            return new PlayerState();
        }
    }

    protected void parseLine(String line) {
        String[] keyValue = splitLine(line);
        if (keyValue == null) return;

        final String categoryName = keyValue[0].toLowerCase();
        final String categoryValue = keyValue[1];

        if (categoryName.startsWith("active")) {
            if (categoryName.endsWith("player"))
                tChangePlayer = categoryValue.trim().toLowerCase();
            else if (categoryName.endsWith("phase"))
                tChangePhase = categoryValue.trim().toUpperCase();
            else if (categoryName.endsWith("phaseadvance"))
                tAdvancePhase = categoryValue.trim().toUpperCase();
            return;
        }

        if (categoryName.equals("turn")) {
            turn = Integer.parseInt(categoryValue);
        } else if (categoryName.equals("monarch")) {
            monarch = categoryValue.trim();
        } else if (categoryName.equals("monarchbeginturn")) {
            monarchBeginTurn = categoryValue.trim();
        } else if (categoryName.equals("initiative")) {
            initiative = categoryValue.trim();
        } else if (categoryName.equals("daytime")) {
            dayTime = categoryValue.trim().toLowerCase();
        } else if (categoryName.equals("delayedtrigger")) {
            delayedTriggers.add(categoryValue.trim());
        } else if (categoryName.equals("effect")) {
            effects.add(categoryValue.trim());
        } else if (categoryName.equals("return")) {
            returns.add(categoryValue.trim());
        } else if (categoryName.equals("removesummoningsickness")) {
            removeSummoningSickness = categoryValue.equalsIgnoreCase("true");
        } else if (categoryName.endsWith("life")) {
            getPlayerState(categoryName).life = Integer.parseInt(categoryValue);
        } else if (categoryName.endsWith("counters")) {
            getPlayerState(categoryName).counters = categoryValue;
        } else if (categoryName.endsWith("landsplayed")) {
            getPlayerState(categoryName).landsPlayed = Integer.parseInt(categoryValue);
        } else if (categoryName.endsWith("landsplayedlastturn")) {
            getPlayerState(categoryName).landsPlayedLastTurn = Integer.parseInt(categoryValue);
        } else if (categoryName.endsWith("numringtemptedyou")) {
            getPlayerState(categoryName).numRingTemptedYou = Integer.parseInt(categoryValue);
        } else if (categoryName.endsWith("speed")) {
            getPlayerState(categoryName).speed = Integer.parseInt(categoryValue);
        } else if (categoryName.endsWith("play") || categoryName.endsWith("battlefield")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Battlefield, categoryValue);
        } else if (categoryName.endsWith("hand")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Hand, categoryValue);
        } else if (categoryName.endsWith("graveyard")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Graveyard, categoryValue);
        } else if (categoryName.endsWith("library")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Library, categoryValue);
        } else if (categoryName.endsWith("exile")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Exile, categoryValue);
        } else if (categoryName.endsWith("command")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Command, categoryValue);
        } else if (categoryName.endsWith("sideboard")) {
            getPlayerState(categoryName).cardTexts.put(ZoneType.Sideboard, categoryValue);
        } else if (categoryName.startsWith("ability")) {
            abilityString.put(categoryName.substring("ability".length()), categoryValue);
        } else if (categoryName.endsWith("precast")) {
            getPlayerState(categoryName).precast = categoryValue;
        } else if (categoryName.endsWith("putonstack")) {
            getPlayerState(categoryName).putOnStack = categoryValue;
        } else if (categoryName.endsWith("manapool")) {
            getPlayerState(categoryName).manaPool = categoryValue;
        } else if (categoryName.endsWith("persistentmana")) {
            getPlayerState(categoryName).persistentMana = categoryValue;
        } else {
            System.err.println("Unknown key: " + categoryName);
        }
    }

    /** This state is a game's own, taken by {@link #initFromGame}: put it back as it was, running no replacement effect. */
    public void setRestore(final boolean restore) {
        this.restore = restore;
    }

    /**
     * Leave {@code activephaseadvance} for {@link #advanceDeferred}: the caller first shows every seat the
     * game this text made. Advancing inside the apply runs the step's triggers while a networked seat
     * still holds the old view, and a trigger whose card the seat cannot resolve is asked about with no
     * card, answered null, and dropped.
     */
    public void setDeferAdvance(final boolean defer) {
        this.deferAdvance = defer;
    }

    /** The {@code activephaseadvance} an apply under {@link #setDeferAdvance} left, run now on the game thread. */
    public void advanceDeferred(final Game game) {
        final PhaseType advance = pendingAdvance;
        pendingAdvance = null;
        if (advance == null) return;
        game.getAction().invoke(() -> {
            game.getPhaseHandler().devAdvanceToPhase(advance);
            game.getAction().checkStateEffects(true);
        });
    }

    public void applyToGame(final Game game) {
        game.getAction().invoke(() -> applyGameOnThread(game));
    }

    protected void applyGameOnThread(final Game game) {
        if (game.getPlayers().size() != playerStates.size()) {
            throw new RuntimeException("Non-matching number of players, (" +
                game.getPlayers().size() + " vs. " + playerStates.size() + ")");
        }

        idToCard.clear();
        cardToAttachId.clear();
        cardToEnchantPlayerId.clear();
        cardToRememberedId.clear();
        cardToExiledWithId.clear();
        cardToImprintedId.clear();
        markedDamage.clear();
        cardToChosenClrs.clear();
        cardToChosenCards.clear();
        cardToChosenType.clear();
        cardToChosenType2.clear();
        cardToMergedCards.clear();
        cardToScript.clear();
        cardAttackMap.clear();
        cardToControl.clear();

        int playerTurn = playerStates.indexOf(getPlayerState(tChangePlayer));
        Player newPlayerTurn = game.getPlayers().get(playerTurn);
        PhaseType newPhase = tChangePhase.equalsIgnoreCase("none") ? null : PhaseType.smartValueOf(tChangePhase);
        PhaseType advPhase = tAdvancePhase.equalsIgnoreCase("none") ? null : PhaseType.smartValueOf(tAdvancePhase);

        // Set stack to resolving so things won't trigger/effects be checked right away
        game.getStack().setResolving(true);

        game.getPhaseHandler().devModeSet(newPhase, newPlayerTurn, turn);

        game.getTriggerHandler().setSuppressAllTriggers(true);

        // A restored board already says how each permanent entered (tapped, counters, choices):
        // "enters tapped", "you may pay 2 life" and "enters with N counters" must not run again.
        game.getReplacementHandler().setSuppressAll(restore);
        try {
            for (int i = 0; i < playerStates.size(); i++) {
                setupPlayerState(game.getPlayers().get(i), playerStates.get(i));
            }
        } finally {
            game.getReplacementHandler().setSuppressAll(false);
        }
        handleGamePieces(game);
        handleEffects(game);
        handleReturns(game);
        handleControlLayers(game);
        handleCardAttachments();
        handleChosenEntities();
        handleRememberedEntities();
        handleMergedCards();
        handleScriptExecution(game);
        handleDelayedTriggers(game);
        handlePrecastSpells(game);
        handleMarkedDamage();

        game.getTriggerHandler().setSuppressAllTriggers(false);

        // SAs added to stack cause triggers to fire, as if the relevant SAs were cast
        handleAddSAsToStack(game);

        // Combat only works for 1v1 matches for now (which are the only matches dev mode supports anyway)
        // Note: triggers may fire during combat declarations ("whenever X attacks, ...", etc.)
        if (newPhase == PhaseType.COMBAT_DECLARE_ATTACKERS || newPhase == PhaseType.COMBAT_DECLARE_BLOCKERS) {
            boolean toDeclareBlockers = newPhase == PhaseType.COMBAT_DECLARE_BLOCKERS;
            if (newPlayerTurn != null) {
                handleCombat(game, newPlayerTurn, newPlayerTurn.getSingleOpponent(), toDeclareBlockers);
            }
        }

        game.getStack().setResolving(false);
        game.getStack().unfreezeStack();

        // Advance to a certain phase, activating all triggered abilities
        if (advPhase != null && deferAdvance) {
            pendingAdvance = advPhase;
        } else if (advPhase != null) {
            game.getPhaseHandler().devAdvanceToPhase(advPhase);
        }

        if (removeSummoningSickness) {
            for (Card card : game.getCardsInGame()) {
                card.setSickness(false);
            }
        }

        game.getAction().checkStateEffects(true); //ensure state based effects and triggers are updated

        // prevent interactions with objects from old state
        game.copyLastState();

        // Store snapshot for restoring
        game.stashGameState();

        // Set negative or zero life after state effects if need be, important for some puzzles that rely on
        // pre-setting negative life (e.g. PS_NEO4).
        for (int i = 0; i < playerStates.size(); i++) {
            int life = playerStates.get(i).life;
            if (life <= 0) {
                game.getPlayers().get(i).setLife(life, null);
            }
        }
    }

    /**
     * The monarch, the initiative and day/night, as this text says them. A line that is absent leaves
     * the game's own alone, unless this is a restore ({@link #setRestore}), whose text is the whole
     * game: there, absent is nobody. Recreated as the game left them, so no "becomes the monarch" or
     * "takes the initiative" trigger runs (they are suppressed here in any case).
     */
    private void handleGamePieces(final Game game) {
        if (monarch != null || restore) {
            if (game.getMonarch() != null) game.getMonarch().removeMonarchEffect();
            game.setMonarch(null);
            if (monarch != null) {
                final Player p = holderOf(game, monarch);
                p.createMonarchEffect(holderSet(monarch));
                game.setMonarch(p);
            }
        }
        if (monarchBeginTurn != null || restore) {
            game.setMonarchBeginTurn(monarchBeginTurn == null ? null : holderOf(game, monarchBeginTurn));
        }
        if (initiative != null || restore) {
            if (game.getHasInitiative() != null) game.getHasInitiative().removeInitiativeEffect();
            game.setHasInitiative(null);
            if (initiative != null) {
                final Player p = holderOf(game, initiative);
                game.setHasInitiative(p);
                p.createInitiativeEffect(holderSet(initiative));
            }
        }
        if (dayTime != null || restore) {
            game.setDayTime(dayTime == null ? null : dayTime.equals("night")); // true is night (Game.isNight)
        }
    }

    /**
     * Each card's control in its layers, once every card is in its zone: the base controller below every
     * timestamp, so every control effect outranks it as it did in the game written (a static's timestamp
     * is its host's layer timestamp, 0 on a card this text made), then each control a resolved effect
     * gave, in order, with the condition that ends it put back on its source.
     */
    private void handleControlLayers(final Game game) {
        for (Entry<Card, List<String>> e : cardToControl.entrySet()) {
            final Card c = e.getKey();
            long lastGained = 0;
            for (String info : e.getValue()) {
                final String value = info.substring(info.indexOf(':') + 1);
                if (info.startsWith("BaseController:")) {
                    c.setController(parsePlayerString(game, value), Long.MIN_VALUE);
                } else if (info.startsWith("GainedControl:")) {
                    lastGained = game.getNextTimestamp();
                    c.addTempController(parsePlayerString(game, value), lastGained);
                } else {
                    final int at = value.lastIndexOf('@');
                    final Card source = idToCard.get(Integer.parseInt(value.substring(at + 1)));
                    if (source == null) {
                        System.err.println("ERROR: no card with id " + value.substring(at + 1) + " for " + c + "'s control condition");
                        continue;
                    }
                    forge.game.ability.effects.ControlGainEffect.restoreConditionalControl(c, lastGained, Arrays.asList(value.substring(0, at).split("\\+")), source);
                }
            }
            game.getAction().controllerChangeZoneCorrection(c);
        }
    }

    private Player holderOf(final Game game, final String holder) {
        final int bar = holder.indexOf('|');
        return parsePlayerString(game, (bar < 0 ? holder : holder.substring(0, bar)).toUpperCase());
    }

    private static String holderSet(final String holder) {
        final int bar = holder.indexOf('|');
        return bar < 0 ? null : holder.substring(bar + 1);
    }

    private String processManaPool(ManaPool manaPool) {
        StringBuilder mana = new StringBuilder();
        for (final byte c : ManaAtom.MANATYPES) {
            int amount = manaPool.getAmountOfColor(c);
            for (int i = 0; i < amount; i++) {
                mana.append(MagicColor.toShortString(c)).append(" ");
            }
        }

        return mana.toString().trim();
    }

    private void updateManaPool(Player p, String manaDef, boolean clearPool, boolean persistent) {
        Game game = p.getGame();
        if (clearPool) {
            p.getManaPool().clearPool(false);
        }

        if (!manaDef.isEmpty()) {
            final Card dummy = new Card(-777777, game);
            dummy.setOwner(p);
            final Map<String, String> produced = Maps.newHashMap();
            produced.put("Produced", manaDef);
            if (persistent) {
                produced.put("PersistentMana", "True");
            }
            final AbilityManaPart abMana = new AbilityManaPart(dummy, produced);
            game.getAction().invoke(() -> abMana.produceMana(null));
        }
    }

    private void handleCombat(final Game game, final Player attackingPlayer, final Player defendingPlayer, final boolean toDeclareBlockers) {
        // First we need to ensure that all attackers are declared in the Declare Attackers step,
        // even if proceeding straight to Declare Blockers
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attackingPlayer, turn);

        if (game.getPhaseHandler().getCombat() == null) {
            game.getPhaseHandler().setCombat(new Combat(attackingPlayer));
            game.updateCombatForView();
        }

        Combat combat = game.getPhaseHandler().getCombat();
        for (Entry<Card, Card> attackMap : cardAttackMap.entrySet()) {
            Card attacker = attackMap.getKey();
            Card attacked = attackMap.getValue();

            combat.addAttacker(attacker, attacked == null ? defendingPlayer : attacked);
        }

        // Run the necessary combat events and triggers to set things up correctly as if the
        // attack was actually declared by the attacking player
        Multimap<GameEntity, Card> attackersMap = ArrayListMultimap.create();
        for (GameEntity ge : combat.getDefenders()) {
            attackersMap.putAll(ge, combat.getAttackersOf(ge));
        }
        game.fireEvent(new GameEventAttackersDeclared(attackingPlayer, attackersMap));

        for (final Card c : combat.getAttackers()) {
            CombatUtil.checkDeclaredAttacker(game, c, combat, false);
        }

        game.updateCombatForView();
        game.fireEvent(new GameEventCombatChanged());

        // Gracefully proceed to Declare Blockers, giving priority to the defending player,
        // but only if the stack is empty (otherwise the game will crash).
        game.getStack().addAllTriggeredAbilitiesToStack();
        if (toDeclareBlockers && game.getStack().isEmpty()) {
            game.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DECLARE_BLOCKERS);
        }
    }

    private void handleRememberedEntities() {
        // Remembered: X
        for (Entry<Card, List<String>> rememberedEnts : cardToRememberedId.entrySet()) {
            Card c = rememberedEnts.getKey();
            List<String> ids = rememberedEnts.getValue();

            for (String id : ids) {
                Card tgt = idToCard.get(Integer.parseInt(id));
                c.addRemembered(tgt);
            }
        }

        // Imprinting: X
        for (Entry<Card, List<String>> imprintedCards : cardToImprintedId.entrySet()) {
            Card c = imprintedCards.getKey();
            List<String> ids = imprintedCards.getValue();

            for (String id : ids) {
                Card tgt = idToCard.get(Integer.parseInt(id));
                c.addImprintedCard(tgt);
            }
        }

        // Exiled with X
        for (Entry<Card, String> rememberedEnts : cardToExiledWithId.entrySet()) {
            Card c = rememberedEnts.getKey();
            String id = rememberedEnts.getValue();

            Card exiledWith = idToCard.get(Integer.parseInt(id));
            if (exiledWith != null) {
                exiledWith.addExiledCard(c);
                c.setExiledWith(exiledWith);
                c.setExiledBy(exiledWith.getController());
            }
        }
    }

    private int parseTargetInScript(final String tgtDef) {
        int tgtID;
        if (tgtDef.equalsIgnoreCase("human")) {
            tgtID = TARGET_HUMAN;
        } else if (tgtDef.equalsIgnoreCase("ai")) {
            tgtID = TARGET_AI;
        } else {
            tgtID = Integer.parseInt(tgtDef);
        }

        return tgtID;
    }

    private void handleScriptedTargetingForSA(final Game game, final SpellAbility sa, int tgtID) {
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);

        if (tgtID != TARGET_NONE) {
            switch (tgtID) {
                case TARGET_HUMAN:
                    sa.getTargets().add(human);
                    break;
                case TARGET_AI:
                    sa.getTargets().add(ai);
                    break;
                default:
                    sa.getTargets().add(idToCard.get(tgtID));
                    break;
            }
        }

        if (sa.hasParam("RememberTargets")) {
            sa.getHostCard().addRemembered(sa.getTargets());
        }
    }

    private void handleScriptExecution(final Game game) {
        for (Entry<Card, String> scriptPtr : cardToScript.entrySet()) {
            Card c = scriptPtr.getKey();
            String sPtr = scriptPtr.getValue();

            executeScript(game, c, sPtr);
        }
    }

    private void executeScript(Game game, Card c, String sPtr) {
        executeScript(game, c, sPtr, false);
    }
    private void executeScript(Game game, Card c, String sPtr, boolean putOnStack) {
        int tgtID = TARGET_NONE;
        if (sPtr.contains("->")) {
            String tgtDef = sPtr.substring(sPtr.lastIndexOf("->") + 2);

            tgtID = parseTargetInScript(tgtDef);
            sPtr = sPtr.substring(0, sPtr.lastIndexOf("->"));
        }

        SpellAbility sa = null;
        if (StringUtils.isNumeric(sPtr)) {
            int numSA = Integer.parseInt(sPtr);
            if (c.getSpellAbilities().size() >= numSA) {
                sa = c.getSpellAbilities().get(numSA);
            } else {
                System.err.println("ERROR: Unable to find SA with index " + numSA + " on card " + c + " to execute!");
            }
        } else {
            // Special handling for keyworded abilities
            if (sPtr.startsWith("KW#")) {
                String kwName = sPtr.substring(3);
                FCollectionView<SpellAbility> saList = c.getSpellAbilities();

                if (kwName.equals("Awaken") || kwName.equals("AwakenOnly")) {
                    // AwakenOnly only creates the Awaken effect, while Awaken precasts the whole spell with Awaken
                    for (SpellAbility ab : saList) {
                        if (ab.getDescription().startsWith("Awaken")) {
                            ab.setActivatingPlayer(c.getController());
                            // target for Awaken is set in its first subability
                            handleScriptedTargetingForSA(game, ab.getSubAbility(), tgtID);
                            sa = kwName.equals("AwakenOnly") ? ab.getSubAbility() : ab;
                        }
                    }
                    if (sa == null) {
                        System.err.println("ERROR: Could not locate keyworded ability Awaken in card " + c + " to execute!");
                        return;
                    }
                }
            } else {
                // SVar-based script execution
                String svarValue = "";

                if (sPtr.startsWith("CustomScript:")) {
                    // A custom line defined in the game state file
                    svarValue = sPtr.substring(sPtr.indexOf(":") + 1);
                } else {
                    // A SVar from the card script file
                    if (!c.hasSVar(sPtr)) {
                        System.err.println("ERROR: Unable to find SVar " + sPtr + " on card " + c + " + to execute!");
                        return;
                    }

                    svarValue = c.getSVar(sPtr);

                    if (tgtID != TARGET_NONE && svarValue.contains("| Defined$")) {
                        // We want a specific target, so try to undefine a predefined target if possible
                        svarValue = TextUtil.fastReplace(svarValue, "| Defined$", "| Undefined$");
                        if (tgtID == TARGET_HUMAN || tgtID == TARGET_AI) {
                            svarValue += " | ValidTgts$ Player";
                        } else {
                            svarValue += " | ValidTgts$ Card";
                        }
                    }
                }

                sa = AbilityFactory.getAbility(svarValue, c);
                if (sa == null) {
                    System.err.println("ERROR: Unable to generate ability for SVar " + svarValue);
                }
            }
        }

        if (sa != null) {
            sa.setActivatingPlayer(c.getController());
        }
        handleScriptedTargetingForSA(game, sa, tgtID);

        if (putOnStack) {
            game.getStack().addAndUnfreeze(sa);
        } else {
            sa.resolve();

            // resolve subabilities
            SpellAbility subSa = sa.getSubAbility();
            while (subSa != null) {
                subSa.resolve();
                subSa = subSa.getSubAbility();
            }
        }
    }

    private void handlePrecastSpells(final Game game) {
        for (int i = 0; i < playerStates.size(); i++) {
            if (playerStates.get(i).precast != null) {
                String[] spellList = TextUtil.split(playerStates.get(i).precast, ';');
                for (String spell : spellList) {
                    precastSpellFromCard(spell, game.getPlayers().get(i), game);
                }
            }
        }
    }

    private void handleAddSAsToStack(final Game game) {
        for (int i = 0; i < playerStates.size(); i++) {
            if (playerStates.get(i).putOnStack != null) {
                String[] spellList = TextUtil.split(playerStates.get(i).putOnStack, ';');
                for (String spell : spellList) {
                    precastSpellFromCard(spell, game.getPlayers().get(i), game, true);
                }
            }
        }
    }

    private void precastSpellFromCard(String spellDef, final Player activator, final Game game) {
        precastSpellFromCard(spellDef, activator, game, false);
    }
    private void precastSpellFromCard(String spellDef, final Player activator, final Game game, final boolean putOnStack) {
        int tgtID = TARGET_NONE;
        String scriptID = "";

        if (spellDef.contains(":")) {
            // targeting via -> will be handled in executeScript
            scriptID = spellDef.substring(spellDef.indexOf(":") + 1).trim();
            spellDef = spellDef.substring(0, spellDef.indexOf(":")).trim();
        } else if (spellDef.contains("->")) {
            String tgtDef = spellDef.substring(spellDef.indexOf("->") + 2).trim();
            tgtID = parseTargetInScript(tgtDef);
            spellDef = spellDef.substring(0, spellDef.indexOf("->")).trim();
        }

        spellDef = spellDef.replace("^", ":"); // alternate marker for when : is the name of the card

        Card c = null;

        if (StringUtils.isNumeric(spellDef)) {
            // Precast from a specific host
            c = idToCard.get(Integer.parseInt(spellDef));
            if (c == null) {
                System.err.println("ERROR: Could not find a card with ID " + spellDef + " to precast!");
                return;
            }
        } else {
            // Precast from a card by name
            PaperCard pc = StaticData.instance().getCommonCards().getCard(spellDef);

            if (pc == null) {
                System.err.println("ERROR: Could not find a card with name " + spellDef + " to precast!");
                return;
            }

            c = Card.fromPaperCard(pc, activator);
        }

        if (!scriptID.isEmpty()) {
            executeScript(game, c, scriptID, putOnStack);
            return;
        }

        SpellAbility sa;
        if (!c.getName().equals(spellDef) && c.hasAlternateState() && spellDef.equals(c.getAlternateState().getName())) {
            sa = c.getAlternateState().getFirstSpellAbility();
        } else {
            sa = c.getFirstSpellAbility();
        }

        sa.setActivatingPlayer(activator);

        handleScriptedTargetingForSA(game, sa, tgtID);

        if (putOnStack) {
            game.getStack().addAndUnfreeze(sa);
        } else {
            sa.resolve();
        }
    }

    private void handleMarkedDamage() {
        for (Entry<Card, Integer> entry : markedDamage.entrySet()) {
            Card c = entry.getKey();
            Integer dmg = entry.getValue();

            c.setDamage(dmg);
        }
    }

    private void handleChosenEntities() {
        // TODO: the AI still gets to choose something (and the notification box pops up) before the
        // choice is overwritten here. Somehow improve this so that there is at least no notification
        // about the choice that will be force-changed anyway.

        // Chosen colors
        for (Entry<Card, List<String>> entry : cardToChosenClrs.entrySet()) {
            Card c = entry.getKey();
            List<String> colors = entry.getValue();

            c.setChosenColors(colors);
        }

        // Chosen type
        for (Entry<Card, String> entry : cardToChosenType.entrySet()) {
            Card c = entry.getKey();
            c.setChosenType(entry.getValue());
        }

        // Chosen type 2
        for (Entry<Card, String> entry : cardToChosenType2.entrySet()) {
            Card c = entry.getKey();
            c.setChosenType2(entry.getValue());
        }

        // Named card
        for (Entry<Card, List<String>> entry : cardToNamedCard.entrySet()) {
            Card c = entry.getKey();
            for (String s : entry.getValue()) {
                c.addNamedCard(s);
            }
        }

        // Chosen cards
        for (Entry<Card, CardCollection> entry : cardToChosenCards.entrySet()) {
            Card c = entry.getKey();
            c.setChosenCards(entry.getValue());
        }
    }

    private void handleCardAttachments() {
        // Unattach all permanents first
        for (Entry<Card, Integer> entry : cardToAttachId.entrySet()) {
            Card attachedTo = idToCard.get(entry.getValue());
            attachedTo.unAttachAllCards(attachedTo);
        }

        // Attach permanents by ID
        for (Entry<Card, Integer> entry : cardToAttachId.entrySet()) {
            Card attachedTo = idToCard.get(entry.getValue());
            Card attacher = entry.getKey();
            if (attacher.isAttachment()) {
                attacher.attachToEntity(attachedTo, null, true);
            }
        }

        // Enchant players
        for (Entry<Card, Player> entry : cardToEnchantPlayerId.entrySet()) {
            entry.getKey().attachToEntity(entry.getValue(), null);
        }
    }

    private void handleMergedCards() {
        for (Entry<Card, List<String>> entry : cardToMergedCards.entrySet()) {
            Card mergedTo = entry.getKey();
            for (String mergedCardName : entry.getValue()) {
                Card c;
                PaperCard pc = StaticData.instance().getCommonCards().getCard(mergedCardName. replace("^", ","));
                if (pc == null) {
                    System.err.println("ERROR: Tried to create a non-existent card named " + mergedCardName + " (as a merged card) when loading game state!");
                    continue;
                }

                c = Card.fromPaperCard(pc, mergedTo.getOwner());
                emulateMergeViaMutate(mergedTo, c);
            }
        }
    }

    private void emulateMergeViaMutate(Card top, Card bottom) {
        if (top == null || bottom == null) {
            System.err.println("ERROR: Tried to call emulateMergeViaMutate with a null card!");
            return;
        }

        Game game = top.getGame();

        bottom.setMergedToCard(top);
        if (!top.hasMergedCard()) {
            top.addMergedCard(top);
        }
        top.addMergedCard(bottom);

        top.removeMutatedStates();

        final long ts = game.getNextTimestamp();
        top.setMutatedTimestamp(ts);
        if (top.getCurrentStateName() != CardStateName.FaceDown) {
            final CardCloneStates mutatedStates = CardFactory.getMutatedCloneStates(top, null/*FIXME*/);
            top.addCloneState(mutatedStates, ts);
        }
        bottom.setTapped(top.isTapped());
        bottom.setFlipped(top.isFlipped());
        top.setTimesMutated(top.getTimesMutated() + 1);
        top.updateTokenView();

        // TODO: Merged commanders aren't supported yet
    }

    private void applyCountersToGameEntity(GameEntity entity, String counterString) {
        entity.setCounters(HashMultiset.create());
        String[] allCounterStrings = counterString.split(",");
        for (final String counterPair : allCounterStrings) {
            String[] pair = counterPair.split("=", 2);
            entity.addCounterInternal(CounterType.getType(pair[0]), Integer.parseInt(pair[1]), null, false, null, null);
        }
    }

    private void setupPlayerState(final Player p, final PlayerState state) {
        // Lock check static as we setup player state

        // Clear all zones first, this ensures that any lingering cards and effects (e.g. in command zone) get cleared up
        // before setting up a new state
        for (ZoneType zt : ZONES.keySet()) {
            p.getZone(zt).removeAllCards(true);
        }

        p.getCommanders().clear();
        p.clearTheRing();

        Map<ZoneType, CardCollectionView> playerCards = new EnumMap<>(ZoneType.class);
        for (Entry<ZoneType, String> kv : state.cardTexts.entrySet()) {
            String value = kv.getValue();
            playerCards.put(kv.getKey(), processCardsForZone(value.isEmpty() ? new String[0] : value.split(";"), p));
        }

        if (state.life >= 0) p.setLife(state.life, null);
        p.setLandsPlayedThisTurn(state.landsPlayed);
        p.setLandsPlayedLastTurn(state.landsPlayedLastTurn);
        p.setNumRingTemptedYou(state.numRingTemptedYou);
        p.setSpeed(state.speed);

        p.clearPaidForSA();

        for (Entry<ZoneType, CardCollectionView> kv : playerCards.entrySet()) {
            PlayerZone zone = p.getZone(kv.getKey());
            if (kv.getKey() == ZoneType.Battlefield) {
                List<Card> cards = new ArrayList<>();
                for (final Card c : kv.getValue()) {
                    if (c.isToken()) {
                        cards.add(c);
                    }
                }
                zone.setCards(cards);
                for (final Card c : kv.getValue()) {
                    if (c.isToken()) {
                        continue;
                    }
                    boolean tapped = c.isTapped();
                    boolean sickness = c.hasSickness();
                    Multiset<CounterType> counters = c.getCounters();
                    // Note: Not clearCounters() since we want to keep the counters var as-is.
                    c.setCounters(HashMultiset.create());
                    if (c.isAura()) {
                        // dummy "enchanting" to indicate that the card will be force-attached elsewhere
                        // (will be overridden later, so the actual value shouldn't matter)

                        //FIXME it shouldn't be able to attach itself
                        c.setEntityAttachedTo(new CardCopyService(c).copyCard(true));
                    }

                    if (cardsWithoutETBTrigs.contains(c)) {
                        p.getGame().getAction().moveTo(ZoneType.Battlefield, c, null, null);
                    } else {
                        p.getZone(ZoneType.Hand).add(c);
                        p.getGame().getAction().moveToPlay(c, null, null);
                    }

                    c.setTapped(tapped);
                    c.setSickness(sickness);
                    c.setCounters(counters);
                }
            } else {
                zone.setCards(kv.getValue());
            }
        }
        if (!p.getCommanders().isEmpty())
            p.createCommanderEffect(); //Original one was lost, and the one made by addCommander would have been erased by setCards.

        updateManaPool(p, state.manaPool, true, false);
        updateManaPool(p, state.persistentMana, false, true);

        if (!state.counters.isEmpty()) {
            applyCountersToGameEntity(p, state.counters);
        }
        if (state.numRingTemptedYou > 0) {
            //setup all levels
            for (int i = 1; i <= state.numRingTemptedYou; i++) {
                if (i > 4)
                    break;
                p.setRingLevel(i);
            }
        }
        if (state.speed > 0) p.createSpeedEffect();
    }

    /**
     * <p>
     * processCardsForZone.
     * </p>
     *
     * @param data
     *            an array of {@link java.lang.String} objects.
     * @param player
     *            a {@link forge.game.player.Player} object.
     * @return a {@link CardCollectionView} object.
     */
    private CardCollectionView processCardsForZone(final String[] data, final Player player) {
        final CardCollection cl = new CardCollection();
        for (final String element : data) {
            final String[] cardinfo = element.trim().split("\\|");

            String setCode = null;
            for (final String info : cardinfo) {
                if (info.startsWith("Set:")) {
                    setCode = info.substring(info.indexOf(':') + 1);
                    break;
                }
            }

            int artID = -1;
            for (final String info : cardinfo) {
                if (info.startsWith("Art:")) {
                    try {
                        artID = Integer.parseInt(info.substring(info.indexOf(':') + 1));
                    } catch (Exception e) {
                        break;
                    }
                    break;
                }
            }

            Card c;
            boolean hasSetCurSet = false;
            if (cardinfo[0].startsWith("t:")) {
                // TODO Make sure Game State conversion works with new tokens
                String tokenStr = cardinfo[0].substring(2);
                int image = tokenStr.lastIndexOf("Image:");
                if (image >= 0) tokenStr = tokenStr.substring(0, image) + tokenStr.substring(image).replace('/', '|');
                c = new TokenInfo(tokenStr).makeOneToken(player);
            } else if (cardinfo[0].startsWith("T:")) {
                String tokenStr = cardinfo[0].substring(2);
                PaperToken token = StaticData.instance().getAllTokens().getToken(tokenStr,
                        setCode != null ? setCode : CardEdition.UNKNOWN_CODE);
                if (token == null) {
                    System.err.println("ERROR: Tried to create a non-existent token named " + cardinfo[0] + " when loading game state!");
                    continue;
                }
                c = CardFactory.getCard(token, player, player.getGame());
            } else {
                PaperCard pc = StaticData.instance().getCommonCards().getCard(cardinfo[0], setCode, artID);
                if (pc == null) {
                    System.err.println("ERROR: Tried to create a non-existent card named " + cardinfo[0] + " (set: " + (setCode == null ? "any" : setCode) + ") when loading game state!");
                    continue;
                }

                c = Card.fromPaperCard(pc, player);
                if (setCode != null) {
                    hasSetCurSet = true;
                }
            }
            c.setSickness(false);

            for (final String info : cardinfo) {
                if (info.startsWith("Tapped")) {
                    c.tap(false, null, null);
                } else if (info.startsWith("Renowned")) {
                    c.setRenowned(true);
                } else if (info.startsWith("Solved")) {
                    c.setSolved(true);
                } else if (info.startsWith("Saddled")) {
                    c.setSaddled(true);
                } else if (info.startsWith("Suspected")) {
                    c.setSuspected(true);
                } else if (info.startsWith("Monstrous")) {
                    c.setMonstrous(true);
                } else if (info.startsWith("PhasedOut")) {
                    String tgt = info.substring(info.indexOf(':') + 1);
                    c.setPhasedOut(parsePlayerString(player.getGame(), tgt));
                } else if (info.startsWith("Counters:")) {
                    applyCountersToGameEntity(c, info.substring(info.indexOf(':') + 1));
                } else if (info.startsWith("SummonSick")) {
                    c.setSickness(true);
                } else if (info.startsWith("FaceDown")) {
                    c.turnFaceDown(true);
                    if (info.endsWith("Manifested")) {
                        c.setManifested(new SpellAbility.EmptySa(ApiType.Manifest, c));
                    }
                    if (info.endsWith("Cloaked")) {
                        c.setCloaked(new SpellAbility.EmptySa(ApiType.Cloak, c));
                    }
                } else if (info.startsWith("Transformed") || info.startsWith("Modal")) {
                    c.setState(CardStateName.Backside, true);
                    c.setBackSide(true);
                } else if (info.startsWith("Flipped")) {
                    c.setState(CardStateName.Flipped, true);
                } else if (info.startsWith("Meld")) {
                    if (info.indexOf(':') > 0) {
                        String meldCardName = info.substring(info.indexOf(':') + 1).replace("^", ",");
                        Card meldTarget;
                        PaperCard pc = StaticData.instance().getCommonCards().getCard(meldCardName);
                        if (pc == null) {
                            System.err.println("ERROR: Tried to create a non-existent card named " + meldCardName + " (as a MeldedWith card) when loading game state!");
                            continue;
                        }
                        meldTarget = Card.fromPaperCard(pc, c.getOwner());
                        c.setMeldedWith(meldTarget);
                    }
                    c.setState(CardStateName.Meld, true);
                    c.setBackSide(true);
                }
                else if (info.startsWith("OnAdventure")) {
                    String abAdventure = "DB$ Effect | RememberObjects$ Self | StaticAbilities$ Play | ForgetOnMoved$ Exile | Duration$ Permanent | ConditionDefined$ Self | ConditionPresent$ Card.!copiedSpell";
                    SpellAbility saAdventure = AbilityFactory.getAbility(abAdventure, c);
                    StringBuilder sbPlay = new StringBuilder();
                    sbPlay.append("Mode$ Continuous | MayPlay$ True | EffectZone$ Command | Affected$ Card.IsRemembered+nonAdventure");
                    sbPlay.append(" | AffectedZone$ Exile | Description$ You may cast the card.");
                    saAdventure.setSVar("Play", sbPlay.toString());
                    saAdventure.setActivatingPlayer(c.getOwner());
                    saAdventure.resolve();
                    c.setExiledWith(c); // This seems to be the way it's set up internally. Potentially not needed here?
                    c.setExiledBy(c.getController());
                } else if (info.startsWith("IsCommander")) {
                    player.addCommander(c);
                } else if (info.startsWith("IsRingBearer")) {
                    c.setRingBearer(true);
                    player.setRingBearer(c);
                } else if (info.startsWith("Id:")) {
                    int id = Integer.parseInt(info.substring(3));
                    idToCard.put(id, c);
                } else if (info.startsWith("Attaching:") /*deprecated*/ || info.startsWith("AttachedTo:")) {
                    int id = Integer.parseInt(info.substring(info.indexOf(':') + 1));
                    cardToAttachId.put(c, id);
                } else if (info.startsWith("EnchantingPlayer:")) {
                    String tgt = info.substring(info.indexOf(':') + 1);
                    cardToEnchantPlayerId.put(c, parsePlayerString(player.getGame(), tgt));
                } else if (info.startsWith("Owner:")) {
                    String owner = info.substring(info.indexOf(':') + 1);
                    Player controller = c.getController();
                    c.setOwner(parsePlayerString(player.getGame(), owner));
                    c.setController(controller, c.getGame().getNextTimestamp());
                } else if (info.startsWith("BaseController:") || info.startsWith("GainedControl:") || info.startsWith("ControlUntil:")) {
                    // Put on once every player's zones are set (handleControlLayers): a card whose base
                    // controller is another player moves to that player's battlefield, and a later
                    // player's setup would clear it from the game.
                    cardToControl.computeIfAbsent(c, k -> new ArrayList<>()).add(info);
                } else if (info.startsWith("Ability:")) {
                    String abString = info.substring(info.indexOf(':') + 1).toLowerCase();
                    c.addSpellAbility(AbilityFactory.getAbility(abilityString.get(abString), c));
                } else if (info.startsWith("Damage:")) {
                    int dmg = Integer.parseInt(info.substring(info.indexOf(':') + 1));
                    markedDamage.put(c, dmg);
                } else if (info.startsWith("ChosenColor:")) {
                    cardToChosenClrs.put(c, Arrays.asList(info.substring(info.indexOf(':') + 1).split(",")));
                } else if (info.startsWith("ChosenType:")) {
                    cardToChosenType.put(c, info.substring(info.indexOf(':') + 1));
                } else if (info.startsWith("ChosenType2:")) {
                    cardToChosenType2.put(c, info.substring(info.indexOf(':') + 1));
                } else if (info.startsWith("ChosenCards:")) {
                    CardCollection chosen = new CardCollection();
                    String[] idlist = info.substring(info.indexOf(':') + 1).split(",");
                    for (String id : idlist) {
                        chosen.add(idToCard.get(Integer.parseInt(id)));
                    }
                    cardToChosenCards.put(c, chosen);
                } else if (info.startsWith("MergedCards:")) {
                    List<String> cardNames = Arrays.asList(info.substring(info.indexOf(':') + 1).split(","));
                    cardToMergedCards.put(c, cardNames);
                } else if (info.startsWith("NamedCard:")) {
                    List<String> cardNames = Arrays.asList(info.substring(info.indexOf(':') + 1).split(","));
                    cardToNamedCard.put(c, cardNames);
                } else if (info.startsWith("ExecuteScript:")) {
                    cardToScript.put(c, info.substring(info.indexOf(':') + 1));
                } else if (info.startsWith("RememberedCards:")) {
                    cardToRememberedId.put(c, Arrays.asList(info.substring(info.indexOf(':') + 1).split(",")));
                } else if (info.startsWith("Imprinting:")) {
                    cardToImprintedId.put(c, Arrays.asList(info.substring(info.indexOf(':') + 1).split(",")));
                } else if (info.startsWith("ExiledWith:")) {
                    cardToExiledWithId.put(c, info.substring(info.indexOf(':') + 1));
                } else if (info.startsWith("Attacking")) {
                    if (info.contains(":")) {
                        int id = Integer.parseInt(info.substring(info.indexOf(':') + 1));
                        cardAttackMap.put(c, idToCard.get(id));
                    } else {
                        cardAttackMap.put(c, null);
                    }
                } else if (info.equals("NoETBTrigs")) {
                    cardsWithoutETBTrigs.add(c);
                } else if (info.equals("Foretold")) {
                    c.setForetold(true);
                    c.turnFaceDown(true);
                    c.addMayLookFaceDownExile(c.getOwner());
                } else if (info.equals("ForetoldThisTurn")) {
                    c.setTurnInZone(turn);
                } else if (info.equals("IsToken")) {
                    c.setGamePieceType(GamePieceType.TOKEN);
                } else if (info.startsWith("ClassLevel:")) {
                    c.setClassLevel(Integer.parseInt(info.substring(info.indexOf(':') + 1)));
                } else if (info.startsWith("UnlockedRoom:")) {
                    c.unlockRoom(c.getController(), CardStateName.smartValueOf(info.substring(info.indexOf(':') + 1)));
                }
            }

            if (!hasSetCurSet && !c.isToken()) {
                c.setSetCode(c.getMostRecentSet());
            }

            cl.add(c);
        }
        return cl;
    }
}
