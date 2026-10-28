package forge.game.ability.effects;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rules a set of cards chosen at once must meet, for the effects that used to ask a person one
 * card at a time and narrow the list after each pick. Asking once, the whole set is checked here
 * and asked again until it holds; the AI keeps the one-at-a-time path.
 *
 * <ul>
 * <li>Pairwise: {@code DifferentNames}/{@code WithDifferentNames}, {@code DifferentCMC},
 *     {@code DifferentPower}/{@code WithDifferentPowers}, {@code ShareLandType}.</li>
 * <li>Totals: {@code WithTotalCMC}, {@code WithTotalPower} (at most), {@code WithTotalCardTypes}
 *     (card types among them, at least).</li>
 * <li>Slots ({@link #slots}): one card for each of a list of kinds — {@code ChangeZone}'s
 *     {@code ChangeType$ EACH a & b}, {@code ChooseCard}'s {@code ChooseEach}, {@code EachBasicType}
 *     and {@code EachDifferentPower}, {@code Dig}'s {@code ForEachColorPair}. Every chosen card holds a slot
 *     of its own. With {@link #mustFill} the slots are filled: by as many different cards as can hold
 *     one where each kind is a card found ({@link #distinct}), or else every slot some offered card fits
 *     answered by a chosen card that fits it, one card answering several.</li>
 * </ul>
 * Choosing nothing meets every rule but {@link #mustFill}'s.
 */
public final class ChosenSet {

    private ChosenSet() { }

    private static final Set<ApiType> APIS = Set.of(ApiType.ChangeZone, ApiType.ChooseCard, ApiType.Dig, ApiType.CopyPermanent);

    private static final List<String> RULES = List.of("DifferentNames", "WithDifferentNames", "DifferentCMC", "DifferentPower",
            "WithDifferentPowers", "ShareLandType", "WithTotalCMC", "WithTotalPower", "WithTotalCardTypes");

    public static boolean differentNames(final SpellAbility sa) {
        return sa.hasParam("DifferentNames") || sa.hasParam("WithDifferentNames");
    }

    public static boolean differentPowers(final SpellAbility sa) {
        return sa.hasParam("DifferentPower") || sa.hasParam("WithDifferentPowers");
    }

    /** Whether a set chosen for this ability at once is checked here. */
    public static boolean constrains(final SpellAbility sa) {
        if (sa == null || !APIS.contains(sa.getApi())) {
            return false;
        }
        for (final String rule : RULES) {
            if (sa.hasParam(rule)) {
                return true;
            }
        }
        return slotted(sa);
    }

    /** Whether the ability chooses one card for each of a list of kinds ({@link #slots}). */
    public static boolean slotted(final SpellAbility sa) {
        if (sa == null || sa.getApi() == null) {
            return false;
        }
        switch (sa.getApi()) {
            case ChangeZone:
                return sa.getParamOrDefault("ChangeType", "").startsWith("EACH");
            case ChooseCard:
                return sa.hasParam("ChooseEach") || sa.hasParam("EachBasicType") || sa.hasParam("EachDifferentPower");
            case Dig:
                return sa.hasParam("ForEachColorPair");
            default:
                return false;
        }
    }

    /**
     * Whether the slots must be filled ({@link #meets}): a {@code Mandatory} search or
     * {@code ChooseEach}, and every {@code EachBasicType}, {@code EachDifferentPower} and {@code ForEachColorPair}, whose
     * one-at-a-time asks could not be declined.
     */
    public static boolean mustFill(final SpellAbility sa) {
        if (!slotted(sa)) {
            return false;
        }
        return sa.hasParam("Mandatory") || sa.hasParam("EachBasicType") || sa.hasParam("EachDifferentPower") || sa.hasParam("ForEachColorPair");
    }

    /**
     * Whether each kind must be a different card: what a search, a reveal or a dig finds is one card
     * found once, whatever kinds it is. A {@code ChooseCard} choice of permanents is not — its rulings
     * let one permanent count as every kind it is (Cataclysm, Global Ruin, Sundering Titan).
     */
    public static boolean distinct(final SpellAbility sa) {
        return slotted(sa) && sa.getApi() != ApiType.ChooseCard;
    }

    /** One slot: the kind it asks for, as the ability names it, and the cards that fit it. */
    public record Slot(String kind, CardCollection fits) { }

    /** The cards of each slot of {@link #named}. */
    public static List<CardCollection> slots(final SpellAbility sa, final Iterable<Card> cards) {
        final List<CardCollection> out = new ArrayList<>();
        for (final Slot slot : named(sa, cards)) {
            out.add(slot.fits());
        }
        return out;
    }

    /**
     * Each slot, with the cards of {@code cards} that fit it, in the order the one-at-a-time asks
     * went: each {@code EACH} type {@code ChangeNum} times, each {@code ChooseEach} type (the party's
     * four for {@code Party}), each basic land type in WUBRG order, each power among the cards, each colour pair
     * (named by its colours' letters). Empty for any other ability.
     */
    public static List<Slot> named(final SpellAbility sa, final Iterable<Card> cards) {
        final List<Slot> out = new ArrayList<>();
        if (!slotted(sa)) {
            return out;
        }
        final CardCollection pool = new CardCollection(cards);
        switch (sa.getApi()) {
            case ChangeZone: {
                final int perType = sa.hasParam("ChangeNum") ? AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("ChangeNum"), sa) : 1;
                for (final String type : sa.getParam("ChangeType").substring(5).split(" & ")) {
                    final CardCollection fits = new CardCollection(AbilityUtils.filterListByType(pool, type, sa));
                    for (int i = 0; i < perType; i++) {
                        out.add(new Slot(type.trim(), fits));
                    }
                }
                break;
            }
            case ChooseCard:
                if (sa.hasParam("EachBasicType")) {
                    for (final String type : MagicColor.Constant.BASIC_LANDS) {
                        out.add(new Slot(type, CardLists.getType(pool, type)));
                    }
                } else if (sa.hasParam("EachDifferentPower")) {
                    final TreeMap<Integer, CardCollection> byPower = new TreeMap<>();
                    for (final Card c : pool) {
                        byPower.computeIfAbsent(c.getNetPower(), k -> new CardCollection()).add(c);
                    }
                    byPower.forEach((power, fits) -> out.add(new Slot(String.valueOf(power), fits)));
                } else {
                    final String each = sa.getParam("ChooseEach");
                    final Collection<String> types = each.equals("Party") ? CardType.Constant.PARTY_TYPES : Arrays.asList(each.split(" & "));
                    for (final String type : types) {
                        out.add(new Slot(type, CardLists.filter(pool, CardPredicates.isType(type))));
                    }
                }
                break;
            case Dig:
                for (final byte pair : MagicColor.COLORPAIR) {
                    final StringBuilder letters = new StringBuilder();
                    for (final byte color : MagicColor.WUBRG) {
                        if ((pair & color) != 0) {
                            letters.append(MagicColor.toShortString(color));
                        }
                    }
                    out.add(new Slot(letters.toString(), CardLists.filter(pool, CardPredicates.isExactlyColor(pair))));
                }
                break;
            default:
                break;
        }
        return out;
    }

    /** The cards that fit at least one slot, in {@code cards}' order. */
    public static CardCollection fitting(final List<CardCollection> slots, final Iterable<Card> cards) {
        final CardCollection out = new CardCollection();
        for (final Card c : cards) {
            for (final CardCollection slot : slots) {
                if (slot.contains(c)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** How many of {@code chosen} can hold a slot of their own at once (a maximum matching). */
    public static int matched(final List<CardCollection> slots, final List<Card> chosen) {
        final Map<Integer, Card> holder = new HashMap<>();
        int n = 0;
        for (final Card c : chosen) {
            if (place(slots, c, holder, new HashSet<>())) {
                n++;
            }
        }
        return n;
    }

    private static boolean place(final List<CardCollection> slots, final Card c, final Map<Integer, Card> holder, final Set<Integer> seen) {
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).contains(c) || !seen.add(i)) {
                continue;
            }
            final Card held = holder.get(i);
            if (held == null || place(slots, held, holder, seen)) {
                holder.put(i, c);
                return true;
            }
        }
        return false;
    }

    /** Whether every slot some card fits holds one of {@code chosen}. */
    public static boolean fills(final List<CardCollection> slots, final List<Card> chosen) {
        for (final CardCollection slot : slots) {
            if (!slot.isEmpty() && chosen.stream().noneMatch(slot::contains)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code chosen}, chosen at once from {@code offered}, meets every rule of {@code sa}. With
     * {@link #mustFill}, a {@link #distinct} set holds as many slots as any set of offered cards could;
     * any other covers every slot some offered card fits.
     */
    public static boolean meets(final List<Card> chosen, final SpellAbility sa, final Iterable<Card> offered) {
        if (mustFill(sa)) {
            final List<CardCollection> slots = slots(sa, offered);
            final boolean filled = distinct(sa)
                    ? matched(slots, new ArrayList<>(new CardCollection(chosen))) >= matched(slots, fitting(slots, offered))
                    : fills(slots, chosen);
            if (!filled) {
                return false;
            }
        }
        return meets(chosen, sa);
    }

    /** Whether {@code chosen} meets every rule of {@code sa} but {@link #mustFill}'s. */
    public static boolean meets(final List<Card> chosen, final SpellAbility sa) {
        if (chosen.isEmpty()) {
            return true;
        }
        for (int i = 0; i < chosen.size(); i++) {
            for (int j = i + 1; j < chosen.size(); j++) {
                final Card a = chosen.get(i);
                final Card b = chosen.get(j);
                if (differentNames(sa) && a.sharesNameWith(b)) {
                    return false;
                }
                if (sa.hasParam("DifferentCMC") && a.sharesCMCWith(b)) {
                    return false;
                }
                if (differentPowers(sa) && a.getNetPower() == b.getNetPower()) {
                    return false;
                }
                if (sa.hasParam("ShareLandType") && !a.sharesLandTypeWith(b)) {
                    return false;
                }
            }
        }
        final Card source = sa.getHostCard();
        if (sa.hasParam("WithTotalCMC")) {
            final int most = AbilityUtils.calculateAmount(source, sa.getParam("WithTotalCMC"), sa);
            if (most >= 0 && chosen.stream().mapToInt(Card::getCMC).sum() > most) {
                return false;
            }
        }
        if (sa.hasParam("WithTotalPower")) {
            final int most = AbilityUtils.calculateAmount(source, sa.getParam("WithTotalPower"), sa);
            if (most >= 0 && chosen.stream().mapToInt(Card::getNetPower).sum() > most) {
                return false;
            }
        }
        if (sa.hasParam("WithTotalCardTypes")) {
            final int least = AbilityUtils.calculateAmount(source, sa.getParam("WithTotalCardTypes"), sa);
            final Set<CardType.CoreType> types = Sets.newHashSet();
            for (final Card c : chosen) {
                Iterables.addAll(types, c.getType().getCoreTypes());
            }
            if (types.size() < least) {
                return false;
            }
        }
        if (slotted(sa)) {
            final List<Card> distinct = new ArrayList<>(new CardCollection(chosen));
            if (matched(slots(sa, distinct), distinct) < distinct.size()) {
                return false;
            }
        }
        return true;
    }
}
