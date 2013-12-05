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
package forge.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import forge.Command;
import forge.FThreads;
import forge.card.CardCharacteristicName;
import forge.card.CardType;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.ability.effects.AttachEffect;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardUtil;
import forge.game.card.CounterType;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDestroyed;
import forge.game.event.GameEventCardRegenerated;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventFlipCoin;
import forge.game.event.GameEventGameStarted;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.replacement.ReplacementResult;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.ZCTrigger;
import forge.game.zone.PlayerZone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.CollectionSuppliers;
import forge.util.ThreadUtil;
import forge.util.maps.HashMapOfLists;
import forge.util.maps.MapOfLists;

/**
 * Methods for common actions performed during a game.
 * 
 * @author Forge
 * @version $Id$
 */
public class GameAction {
    /**
     * <p>
     * resetActivationsPerTurn.
     * </p>
     */

    private final Game game;

    public GameAction(Game game0) {
        game = game0;
    }

    public final void resetActivationsPerTurn() {
        final List<Card> all = game.getCardsInGame();

        // Reset Activations per Turn
        for (final Card card : all) {
            for (final SpellAbility sa : card.getAllSpellAbilities()) {
                sa.getRestrictions().resetTurnActivations();
            }
        }
    }

    /**
     * <p>
     * changeZone.
     * </p>
     * 
     * @param zoneFrom
     *            a {@link forge.game.zone.PlayerZone} object.
     * @param zoneTo
     *            a {@link forge.game.zone.PlayerZone} object.
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param position TODO
     * @return a {@link forge.game.card.Card} object.
     */
    public Card changeZone(final Zone zoneFrom, Zone zoneTo, final Card c, Integer position) {
        if (c.isCopiedSpell()) {
            if ((zoneFrom != null)) {
                zoneFrom.remove(c);
            }
            return c;
        }
        if (zoneFrom == null && !c.isToken()) {
            zoneTo.add(c, position);
            game.fireEvent(new GameEventCardChangeZone(c, zoneFrom, zoneTo));
            return c;
        }

        boolean toBattlefield = zoneTo.is(ZoneType.Battlefield);
        boolean fromBattlefield = zoneFrom != null && zoneFrom.is(ZoneType.Battlefield);

        //Rule 110.5g: A token that has left the battlefield can't move to another zone
        if (c.isToken() && zoneFrom != null && !fromBattlefield && !zoneFrom.is(ZoneType.Command)) {
            return c;
        }

        boolean suppress = !c.isToken() && zoneFrom.equals(zoneTo);

        Card copied = null;
        Card lastKnownInfo = null;

        if (c.isSplitCard() && !zoneTo.is(ZoneType.Stack)) {
            c.setState(CardCharacteristicName.Original);
        }

        // Don't copy Tokens, copy only cards leaving the battlefield
        if (suppress || !fromBattlefield) {
            lastKnownInfo = c;
            copied = c;
        } else {
            lastKnownInfo = CardUtil.getLKICopy(c);

            if (!c.isToken()) {
                if (c.isCloned()) {
                    c.switchStates(CardCharacteristicName.Cloner, CardCharacteristicName.Original);
                    c.setState(CardCharacteristicName.Original);
                    c.clearStates(CardCharacteristicName.Cloner);
                    if (c.isFlipCard()) {
                        c.clearStates(CardCharacteristicName.Flipped);
                    }
                }

                copied = CardFactory.copyCard(c, false);
                copied.setUnearthed(c.isUnearthed());
                copied.setTapped(false);
                for (final Trigger trigger : copied.getTriggers()) {
                    trigger.setHostCard(copied);
                }
                for (final TriggerReplacementBase repl : copied.getReplacementEffects()) {
                    repl.setHostCard(copied);
                }
                if (c.getName().equals("Skullbriar, the Walking Grave")) {
                    copied.setCounters(c.getCounters());
                }
            } else { //Token
                copied = c;
            }
        }

        if (!suppress) {
            if (zoneFrom == null) {
                copied.getOwner().addInboundToken(copied);
            }

            HashMap<String, Object> repParams = new HashMap<String, Object>();
            repParams.put("Event", "Moved");
            repParams.put("Affected", copied);
            repParams.put("Origin", zoneFrom != null ? zoneFrom.getZoneType() : null);
            repParams.put("Destination", zoneTo.getZoneType());

            ReplacementResult repres = game.getReplacementHandler().run(repParams);
            if (repres != ReplacementResult.NotReplaced) {
                if (game.getStack().isResolving(c) && !zoneTo.is(ZoneType.Graveyard) && repres == ReplacementResult.Prevented) {
                	copied.getOwner().removeInboundToken(copied);
                	return this.moveToGraveyard(c);
                }
                copied.getOwner().removeInboundToken(copied);
                return c;
            }

            if (c.isUnearthed() && (zoneTo.is(ZoneType.Graveyard) || zoneTo.is(ZoneType.Hand) || zoneTo.is(ZoneType.Library))) {
                zoneTo = c.getOwner().getZone(ZoneType.Exile);
                c.setUnearthed(false);
            }
        }

        copied.getOwner().removeInboundToken(copied);

        if (c.wasSuspendCast()) {
            copied = GameAction.addSuspendTriggers(c);
        }

        if (suppress) {
            game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        }

        if (zoneFrom != null) {
            if (fromBattlefield && c.isCreature() && game.getCombat() != null) {
                if (!toBattlefield) {
                    game.getCombat().saveLKI(lastKnownInfo);
                }
                game.getCombat().removeFromCombat(c);
            }
            if ((zoneFrom.is(ZoneType.Library) || zoneFrom.is(ZoneType.PlanarDeck) || zoneFrom.is(ZoneType.SchemeDeck))
                    && zoneFrom == zoneTo && position.equals(zoneFrom.size()) && position != 0) {
                position--;
            }
            zoneFrom.remove(c);
        }

        // "enter the battlefield as a copy" - apply code here
        // but how to query for input here and continue later while the callers assume synchronous result?
        zoneTo.add(copied, position);

        if (fromBattlefield) {
            c.setZone(zoneTo);
        }

        // Need to apply any static effects to produce correct triggers
        checkStaticAbilities();

        // play the change zone sound
        game.fireEvent(new GameEventCardChangeZone(c, zoneFrom, zoneTo));

        final HashMap<String, Object> runParams = new HashMap<String, Object>();
        runParams.put("Card", lastKnownInfo);
        runParams.put("Origin", zoneFrom != null ? zoneFrom.getZoneType().name() : null);
        runParams.put("Destination", zoneTo.getZoneType().name());
        game.getTriggerHandler().runTrigger(TriggerType.ChangesZone, runParams, false);
        // AllZone.getStack().chooseOrderOfSimultaneousStackEntryAll();

        if (suppress) {
            game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
        }

        if (zoneFrom == null) {
            return copied;
        }

        // remove all counters from the card if destination is not the battlefield
        // UNLESS we're dealing with Skullbriar, the Walking Grave
        if (!c.isToken() && (zoneTo.is(ZoneType.Hand) || zoneTo.is(ZoneType.Library) ||
                (!toBattlefield && !c.getName().equals("Skullbriar, the Walking Grave")))) {
            copied.clearCounters();
        }

        if (!c.isToken() && !toBattlefield) {
            copied.getCharacteristics().resetCardColor();
            copied.clearDevoured();
        }

        if (fromBattlefield) {
            if (!c.isToken()) {
                copied.setSuspendCast(false);
                copied.setState(CardCharacteristicName.Original);
            }
            // Soulbond unpairing
            if (c.isPaired()) {
                c.getPairedWith().setPairedWith(null);
                if (!c.isToken()) {
                    c.setPairedWith(null);
                }
            }
            unattachCardLeavingBattlefield(copied);
        } else if (toBattlefield) {
            copied.setTimestamp(game.getNextTimestamp());
            for (String s : copied.getKeyword()) {
                if (s.startsWith("May be played") || s.startsWith("You may look at this card.")
                        || s.startsWith("Your opponent may look at this card.")) {
                    copied.removeAllExtrinsicKeyword(s);
                    copied.removeHiddenExtrinsicKeyword(s);
                }
            }
            for (Player p : game.getPlayers()) {
                copied.getDamageHistory().setNotAttackedSinceLastUpkeepOf(p);
                copied.getDamageHistory().setNotBlockedSinceLastUpkeepOf(p);
                copied.getDamageHistory().setNotBeenBlockedSinceLastUpkeepOf(p);
            }
        } else if (zoneTo.is(ZoneType.Graveyard) || zoneTo.is(ZoneType.Hand) || zoneTo.is(ZoneType.Library)) {
            copied.setTimestamp(game.getNextTimestamp());
            for (String s : copied.getKeyword()) {
                if (s.startsWith("May be played") || s.startsWith("You may look at this card.")
                        || s.startsWith("Your opponent may look at this card.")) {
                    copied.removeAllExtrinsicKeyword(s);
                    copied.removeHiddenExtrinsicKeyword(s);
                }
            }
            copied.clearOptionalCostsPaid();
            if (copied.isFaceDown()) {
                copied.setState(CardCharacteristicName.Original);
            }
        }

        return copied;
    }

    /**
     * TODO: Write javadoc for this method.
     * @param c
     * @param copied
     */
    private void unattachCardLeavingBattlefield(Card copied) {
        // Handle unequipping creatures
        if (copied.isEquipped()) {
            final List<Card> equipments = new ArrayList<Card>(copied.getEquippedBy());
            for (final Card equipment : equipments) {
                if (equipment.isInPlay()) {
                    equipment.unEquipCard(copied);
                }
            }
        }
        // Handle unfortifying lands
        if (copied.isFortified()) {
            final List<Card> fortifications = new ArrayList<Card>(copied.getFortifiedBy());
            for (final Card f : fortifications) {
                if (f.isInPlay()) {
                    f.unFortifyCard(copied);
                }
            }
        }
        // equipment moving off battlefield
        if (copied.isEquipping()) {
            final Card equippedCreature = copied.getEquipping().get(0);
            if (equippedCreature.isInPlay()) {
                copied.unEquipCard(equippedCreature);
            }
        }
        // fortifications moving off battlefield
        if (copied.isFortifying()) {
            final Card fortifiedLand = copied.getFortifying().get(0);
            if (fortifiedLand.isInPlay()) {
                copied.unFortifyCard(fortifiedLand);
            }
        }
        // remove enchantments from creatures
        if (copied.isEnchanted()) {
            final List<Card> auras = new ArrayList<Card>(copied.getEnchantedBy());
            for (final Card aura : auras) {
                aura.unEnchantEntity(copied);
            }
        }
        // unenchant creature if moving aura
        if (copied.isEnchanting()) {
            copied.unEnchantEntity(copied.getEnchanting());
        }
    }

    /**
     * <p>
     * moveTo.
     * </p>
     * 
     * @param zoneTo
     *            a {@link forge.game.zone.PlayerZone} object.
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveTo(final Zone zoneTo, Card c) {
       // FThreads.assertExecutedByEdt(false); // This code must never be executed from EDT,
                                             // use FThreads.invokeInNewThread to run code in a pooled thread

        return moveTo(zoneTo, c, null);
    }

    public final Card moveTo(final Zone zoneTo, Card c, Integer position) {
        FThreads.assertExecutedByEdt(false);

        // Ideally move to should never be called without a prevZone
        // Remove card from Current Zone, if it has one
        final Zone zoneFrom = game.getZoneOf(c);
        // String prevName = prev != null ? prev.getZoneName() : "";

        if (c.hasKeyword("If CARDNAME would leave the battlefield, exile it instead of putting it anywhere else.")
                && !zoneTo.is(ZoneType.Exile)) {
            final PlayerZone removed = c.getOwner().getZone(ZoneType.Exile);
            c.removeAllExtrinsicKeyword("If CARDNAME would leave the battlefield, "
                    + "exile it instead of putting it anywhere else.");
            return this.moveTo(removed, c);
        }

        // Card lastKnownInfo = c;

        c = changeZone(zoneFrom, zoneTo, c, position);

        if (zoneTo.is(ZoneType.Stack)) {
            c.setCastFrom(zoneFrom.getZoneType());
        } else if (zoneFrom == null) {
            c.setCastFrom(null);
        } else if (!(zoneTo.is(ZoneType.Battlefield) && zoneFrom.is(ZoneType.Stack))) {
            c.setCastFrom(null);
        }

        if (c.isAura() && zoneTo.is(ZoneType.Battlefield) && ((zoneFrom == null) || !zoneFrom.is(ZoneType.Stack))
                && !c.isEnchanting()) {
            // TODO Need a way to override this for Abilities that put Auras
            // into play attached to things
            AttachEffect.attachAuraOnIndirectEnterBattlefield(c);
        }

        return c;
    }

    /**
     * Controller change zone correction.
     * 
     * @param c
     *            a Card object
     */
    public final void controllerChangeZoneCorrection(final Card c) {
        System.out.println("Correcting zone for " + c.toString());
        final Zone oldBattlefield = game.getZoneOf(c);
        if (oldBattlefield == null || oldBattlefield.getZoneType() == ZoneType.Stack) {
            return;
        }
        final PlayerZone newBattlefield = c.getController().getZone(oldBattlefield.getZoneType());

        if (newBattlefield == null || oldBattlefield.equals(newBattlefield)) {
            return;
        }

        game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        for (Player p : game.getPlayers()) {
            ((PlayerZoneBattlefield) p.getZone(ZoneType.Battlefield)).setTriggers(false);
        }

        final int tiz = c.getTurnInZone();

        oldBattlefield.remove(c);
        newBattlefield.add(c);
        c.setSickness(true);
        if (c.hasStartOfKeyword("Echo")) {
            c.addExtrinsicKeyword("(Echo unpaid)");
        }
        if (game.getPhaseHandler().inCombat()) {
            game.getCombat().removeFromCombat(c);
        }

        c.setTurnInZone(tiz);

        final HashMap<String, Object> runParams = new HashMap<String, Object>();
        runParams.put("Card", c);
        game.getTriggerHandler().runTrigger(TriggerType.ChangesController, runParams, false);

        game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
        for (Player p : game.getPlayers()) {
            ((PlayerZoneBattlefield) p.getZone(ZoneType.Battlefield)).setTriggers(true);
        }
    }

    /**
     * <p>
     * moveToStack.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToStack(final Card c) {
        final Zone stack = game.getStackZone();
        return this.moveTo(stack, c);
    }

    /**
     * <p>
     * moveToGraveyard.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToGraveyard(Card c) {
        final Player owner = c.getOwner();
        final PlayerZone grave = owner.getZone(ZoneType.Graveyard);
        final PlayerZone exile = owner.getZone(ZoneType.Exile);

        if (c.hasKeyword("If CARDNAME would be put into a graveyard, exile it instead.")) {
            return this.moveTo(exile, c);
        }

        // must put card in OWNER's graveyard not controller's
        c = this.moveTo(grave, c);

        return c;
    }


    /**
     * <p>
     * moveToHand.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToHand(final Card c) {
        final PlayerZone hand = c.getOwner().getZone(ZoneType.Hand);
        return this.moveTo(hand, c);
    }

    /**
     * <p>
     * moveToPlay.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToPlay(final Card c) {
        final PlayerZone play = c.getController().getZone(ZoneType.Battlefield);
        return this.moveTo(play, c);
    }

    /**
     * <p>
     * moveToPlay.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param p
     *            a {@link forge.game.player.Player} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToPlay(final Card c, final Player p) {
        // move to a specific player's Battlefield
        final PlayerZone play = p.getZone(ZoneType.Battlefield);
        return this.moveTo(play, c);
    }

    /**
     * <p>
     * moveToBottomOfLibrary.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToBottomOfLibrary(final Card c) {
        return this.moveToLibrary(c, -1);
    }

    /**
     * <p>
     * moveToLibrary.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToLibrary(final Card c) {
        return this.moveToLibrary(c, 0);
    }

    /**
     * <p>
     * moveToLibrary.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param libPosition
     *            a int.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToLibrary(Card c, int libPosition) {
        final PlayerZone library = c.getOwner().getZone(ZoneType.Library);

        if (libPosition == -1 || libPosition > library.size()) {
            libPosition = library.size();
        }
        return this.changeZone(game.getZoneOf(c), library, c, libPosition);
    }

    /**
     * <p>
     * moveToVariantDeck.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param zone
     *            a {@link forge.game.card.Card} object.
     * @param libPosition
     *            a int.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveToVariantDeck(Card c, ZoneType zone, int deckPosition) {
        final PlayerZone deck = c.getOwner().getZone(zone);
        if (deckPosition == -1 || deckPosition > deck.size()) {
            deckPosition = deck.size();
        }
        return this.changeZone(game.getZoneOf(c), deck, c, deckPosition);
    }

    /**
     * <p>
     * exile.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card exile(final Card c) {
        if (game.isCardExiled(c)) {
            return c;
        }
        final PlayerZone removed = c.getOwner().getZone(ZoneType.Exile);
        return moveTo(removed, c);
    }

    /**
     * Move to.
     * 
     * @param name
     *            the name
     * @param c
     *            the c
     * @return the card
     */
    public final Card moveTo(final ZoneType name, final Card c) {
        return this.moveTo(name, c, 0);
    }

    /**
     * <p>
     * moveTo.
     * </p>
     * 
     * @param name
     *            a {@link java.lang.String} object.
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param libPosition
     *            a int.
     * @return a {@link forge.game.card.Card} object.
     */
    public final Card moveTo(final ZoneType name, final Card c, final int libPosition) {
        // Call specific functions to set PlayerZone, then move onto moveTo
        switch(name) {
            case Hand:          return this.moveToHand(c);
            case Library:       return this.moveToLibrary(c, libPosition);
            case Battlefield:   return this.moveToPlay(c);
            case Graveyard:     return this.moveToGraveyard(c);
            case Exile:         return this.exile(c);
            case Stack:         return this.moveToStack(c);
            case PlanarDeck:    return this.moveToVariantDeck(c, ZoneType.PlanarDeck, libPosition);
            case SchemeDeck:    return this.moveToVariantDeck(c, ZoneType.SchemeDeck, libPosition);
            default: // sideboard will also get there
                return this.moveTo(c.getOwner().getZone(name), c);
        }
    }

    /** */
    public final void checkStaticAbilities() {
        FThreads.assertExecutedByEdt(false);

        if (game.isGameOver()) {
            return;
        }

        // remove old effects
        Set<Card> affectedCards = game.getStaticEffects().clearStaticEffects();
        game.getTriggerHandler().cleanUpTemporaryTriggers();
        game.getReplacementHandler().cleanUpTemporaryReplacements();

        // search for cards with static abilities
        final List<Card> allCards = game.getCardsInGame();
        final ArrayList<StaticAbility> staticAbilities = new ArrayList<StaticAbility>();
        for (final Card card : allCards) {
            for (StaticAbility sa : card.getStaticAbilities()) {
                if (sa.getMapParams().get("Mode").equals("Continuous")) {
                    staticAbilities.add(sa);
                }
            }
        }

        final Comparator<StaticAbility> comp = new Comparator<StaticAbility>() {
            @Override
            public int compare(final StaticAbility a, final StaticAbility b) {
                int layerDelta = a.getLayer() - b.getLayer();
                if (layerDelta != 0) return layerDelta;

                long tsDelta = a.getHostCard().getTimestamp() - b.getHostCard().getTimestamp();
                return tsDelta == 0 ? 0 : tsDelta > 0 ? 1 : -1;
            }
        };
        Collections.sort(staticAbilities, comp);
        for (final StaticAbility stAb : staticAbilities) {
            List<Card> affectedHere = stAb.applyAbility("Continuous");
            if (null != affectedHere) {
                affectedCards.addAll(affectedHere);
            }
        }

        // card state effects like Glorious Anthem
        for (final String effect : game.getStaticEffects().getStateBasedMap().keySet()) {
            final Function<Game, ?> com = GameActionUtil.getCommands().get(effect);
            com.apply(game);
        }

        List<Card> lands = game.getCardsIn(ZoneType.Battlefield);
        GameActionUtil.grantBasicLandsManaAbilities(CardLists.filter(lands, CardPredicates.Presets.LANDS));

        // Exclude cards in hidden zones from update
        Iterator<Card> it = affectedCards.iterator();
        while (it.hasNext()) {
            Card c = it.next();
            if (c.isInZone(ZoneType.Library)) {
                it.remove();
            }
        }

        if (!affectedCards.isEmpty()) {
            game.fireEvent(new GameEventCardStatsChanged(affectedCards));
        }
    }

    /**
     * <p>
     * checkStateEffects.
     * </p>
     */
    public final void checkStateEffects() {
        // sol(10/29) added for Phase updates, state effects shouldn't be
        // checked during Spell Resolution (except when persist-returning
        if (game.getStack().isResolving()) {
            return;
        }

        // final JFrame frame = Singletons.getView().getFrame();
        // if (!frame.isDisplayable()) {
        // return;
        // }

        if (game.isGameOver()) {
            return;
        }

        // Max: I don't know where to put this! - but since it's a state based action, it must be in check state effects
        if (game.getType() == GameType.Archenemy) {
            game.archenemy904_10();
        }

        final boolean refreeze = game.getStack().isFrozen();
        game.getStack().setFrozen(true);

        // do this twice, sometimes creatures/permanents will survive when they
        // shouldn't
        for (int q = 0; q < 9; q++) {
            boolean checkAgain = false;

            this.checkStaticAbilities();

            final HashMap<String, Object> runParams = new HashMap<String, Object>();
            game.getTriggerHandler().runTrigger(TriggerType.Always, runParams, false);

            for (Player p : game.getPlayers()) {
                for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    if (!c.getController().equals(p)) {
                        controllerChangeZoneCorrection(c);
                        c.runChangeControllerCommands();
                        checkAgain = true;
                    }
                }
                for (ZoneType zt : ZoneType.values()) {
                    if (zt == ZoneType.Battlefield) {
                        continue;
                    }
                    for (Card c : p.getCardsIn(zt)) {
                        // If a token is in a zone other than the battlefield, it ceases to exist.
                        checkAgain |= stateBasedAction704_5d(c);
                    }
                }
            }

            for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
                if (c.isCreature() && c.isPaired()) { // Soulbond unpairing (702.93e) - should not be here
                    Card partner = c.getPairedWith();
                    if (!partner.isCreature() || c.getController() != partner.getController() || !c.isInZone(ZoneType.Battlefield)) {
                        c.setPairedWith(null);
                        partner.setPairedWith(null);
                    }
                }

                if (c.isCreature()) {
                    // Rule 704.5f - Destroy (no regeneration) for toughness <= 0
                    if (c.getNetDefense() <= 0) {
                        this.destroyNoRegeneration(c, null);
                        checkAgain = true;
                    } else

                    // Rule 704.5g - Destroy due to lethal damage
                    if (c.getNetDefense() <= c.getDamage()) {
                        this.destroy(c, null);
                        checkAgain = true;
                    }
                }

                checkAgain |= stateBasedAction704_5n(c); // Auras attached to illegal or not attached go to graveyard
                checkAgain |= stateBasedAction704_5p(c); // Equipment and Fortifications

                if (c.isCreature() && c.isEnchanting()) { // Rule 704.5q - Creature attached to an object or player, becomes unattached
                    c.unEnchantEntity(c.getEnchanting());
                    checkAgain = true;
                }

                checkAgain |= stateBasedAction704_5r(c); // annihilate +1/+1 counters with -1/-1 ones

                if (c.getCounters(CounterType.DREAM) > 7 && c.hasKeyword("CARDNAME can't have more than seven dream counters on it.")) {
                    c.subtractCounter(CounterType.DREAM,  c.getCounters(CounterType.DREAM) - 7);
                    checkAgain = true;
                }
            }

            if (game.getTriggerHandler().runWaitingTriggers()) {
                checkAgain = true;
                // Place triggers on stack
                game.getStack().chooseOrderOfSimultaneousStackEntryAll();
            }
            boolean yamazaki = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Brothers Yamazaki")).size() ==  2;
            for (Player p : game.getPlayers()) {
                if (this.handleLegendRule(p, yamazaki)) {
                    checkAgain = true;
                }

                if (this.handlePlaneswalkerRule(p)) {
                    checkAgain = true;
                }
            }

            if (!checkAgain) {
                break; // do not continue the loop
            }
        } // for q=0;q<2

        checkGameOverCondition();

        if (!refreeze) {
            game.getStack().unfreezeStack();
        }
    } // checkStateEffects()

    /**
     * TODO: Write javadoc for this method.
     * @param checkAgain
     * @param c
     * @return
     */
    private boolean stateBasedAction704_5n(Card c) {
        boolean checkAgain = false;
        if (!c.isAura()) {
            return false;
        }

        // Check if Card Aura is attached to is a legal target
        final GameEntity entity = c.getEnchanting();
        SpellAbility sa = c.getSpells().get(0);
        if (c.isBestowed()) {
            for (SpellAbility s : c.getSpellAbilities()) {
                if (s.getApi() == ApiType.Attach && s.hasParam("Bestow")) {
                    sa = s;
                    break;
                }
            }
        }

        TargetRestrictions tgt = null;
        if (sa != null) {
            tgt = sa.getTargetRestrictions();
        }

        if (entity instanceof Card) {
            final Card perm = (Card) entity;
            ZoneType tgtZone = tgt.getZone().get(0);

            if (!perm.isInZone(tgtZone) || !perm.canBeEnchantedBy(c) || (perm.isPhasedOut() && !c.isPhasedOut())) {
                c.unEnchantEntity(perm);
                if (c.isBestowed()) {
                    // TODO : support for tokens
                    ArrayList<String> type = CardFactory.getCard(c.getPaperCard(), c.getController()).getType();
                    final long timestamp = game.getNextTimestamp();
                    c.addChangedCardTypes(type, Lists.newArrayList("Aura"), false, false, false, false, timestamp);
                    c.addChangedCardKeywords(new ArrayList<String>(), Lists.newArrayList("Enchant creature"), false, timestamp);
                    c.setBestow(false);
                    game.fireEvent(new GameEventCardStatsChanged(c));
                }
                else {
                    this.moveToGraveyard(c);
                }
                checkAgain = true;
            }
        } else if (entity instanceof Player) {
            final Player pl = (Player) entity;
            boolean invalid = false;

            if (tgt.canOnlyTgtOpponent() && !c.getController().getOpponent().equals(pl)) {
                invalid = true;
            }
            else if (pl.hasProtectionFrom(c)) {
                invalid = true;
            }
            if (invalid) {
                c.unEnchantEntity(pl);
                this.moveToGraveyard(c);
                checkAgain = true;
            }
        }

        if (c.isInPlay() && !c.isEnchanting()) {
            if (c.isBestowed()) {
                // TODO : support for tokens
                ArrayList<String> type = CardFactory.getCard(c.getPaperCard(), c.getController()).getType();
                final long timestamp = game.getNextTimestamp();
                c.addChangedCardTypes(type, Lists.newArrayList("Aura"), false, false, false, false, timestamp);
                c.addChangedCardKeywords(new ArrayList<String>(), Lists.newArrayList("Enchant creature"), false, timestamp);
                c.setBestow(false);
                game.fireEvent(new GameEventCardStatsChanged(c));
            }
            else {
                this.moveToGraveyard(c);
            }
            checkAgain = true;
        }
        return checkAgain;
    }

    /**
     * TODO: Write javadoc for this method.
     * @param checkAgain
     * @param c
     * @return
     */
    private boolean stateBasedAction704_5p(Card c) {
        boolean checkAgain = false;
        if (c.isEquipped()) {
            final List<Card> equipments = new ArrayList<Card>(c.getEquippedBy());
            for (final Card equipment : equipments) {
                if (!equipment.isInPlay()) {
                    equipment.unEquipCard(c);
                    checkAgain = true;
                }
            }
        } // if isEquipped()

        if (c.isFortified()) {
            final List<Card> fortifications = new ArrayList<Card>(c.getFortifiedBy());
            for (final Card f : fortifications) {
                if (!f.isInPlay()) {
                    f.unFortifyCard(c);
                    checkAgain = true;
                }
            }
        } // if isFortified()

        if (c.isEquipping()) {
            final Card equippedCreature = c.getEquipping().get(0);
            if (!equippedCreature.isCreature() || !equippedCreature.isInPlay()
                    || !equippedCreature.canBeEquippedBy(c)
                    || (equippedCreature.isPhasedOut() && !c.isPhasedOut())) {
                c.unEquipCard(equippedCreature);
                checkAgain = true;
            }
            // make sure any equipment that has become a creature stops equipping
            if (c.isCreature()) {
                c.unEquipCard(equippedCreature);
                checkAgain = true;
            }
        } // if isEquipping()

        if (c.isFortifying()) {
            final Card fortifiedLand = c.getFortifying().get(0);
            if (!fortifiedLand.isLand() || !fortifiedLand.isInPlay()
                    || (fortifiedLand.isPhasedOut() && !c.isPhasedOut())) {
                c.unFortifyCard(fortifiedLand);
                checkAgain = true;
            }
            // make sure any fortification that has become a creature stops fortifying
            if (c.isCreature()) {
                c.unFortifyCard(fortifiedLand);
                checkAgain = true;
            }
        } // if isFortifying()
        return checkAgain;
    }

    /**
     * TODO: Write javadoc for this method.
     * @param checkAgain
     * @param c
     * @return
     */
    private boolean stateBasedAction704_5r(Card c) {
        boolean checkAgain = false;
        // +1/+1 counters should erase -1/-1 counters
        if (c.getCounters(CounterType.P1P1) > 0 && c.getCounters(CounterType.M1M1) > 0) {
            int plusOneCounters = c.getCounters(CounterType.P1P1);
            int minusOneCounters = c.getCounters(CounterType.M1M1);

            if (plusOneCounters >= minusOneCounters) c.getCounters().remove(CounterType.M1M1);
            if (plusOneCounters <= minusOneCounters)  c.getCounters().remove(CounterType.P1P1);

            int diff = plusOneCounters - minusOneCounters;
            if (diff != 0) {
                CounterType ct = diff > 0 ? CounterType.P1P1 : CounterType.M1M1;
                c.getCounters().put(ct, Math.abs(diff));
            }
            checkAgain = true;
        }
        return checkAgain;
    }

    // If a token is in a zone other than the battlefield, it ceases to exist.
    private boolean stateBasedAction704_5d(Card c) {
        boolean checkAgain = false;
        if (c.isToken()) {
            final Zone zoneFrom = game.getZoneOf(c);
            if (!zoneFrom.is(ZoneType.Battlefield) && !zoneFrom.is(ZoneType.Command)) {
                zoneFrom.remove(c);
                checkAgain = true;
            }
        }
        return checkAgain;
    }

    public void checkGameOverCondition() {
        // award loses as SBE
        List<Player> losers = null;
        for (Player p : this.game.getPlayers()) {
            if (p.checkLoseCondition()) { // this will set appropriate outcomes
                // Run triggers
                if (losers == null) {
                    losers = new ArrayList<Player>(3);
                }
                losers.add(p);
            }
        }

        GameEndReason reason = null;
        // Has anyone won by spelleffect?
        for (Player p : this.game.getPlayers()) {
            if (!p.hasWon()) {
                continue;
            }

            // then the rest have lost!
            reason = GameEndReason.WinsGameSpellEffect;
            for (Player pl : this.game.getPlayers()) {
                if (pl.equals(p)) {
                    continue;
                }

                if (!pl.loseConditionMet(GameLossReason.OpponentWon, p.getOutcome().altWinSourceName)) {
                    reason = null; // they cannot lose!
                } else {
                    if (losers == null) {
                        losers = new ArrayList<Player>(3);
                    }
                    losers.add(p);
                }
            }
            break;
        }

        // need a separate loop here, otherwise ConcurrentModificationException is raised
        if (losers != null) {
            for (Player p : losers) {
                this.game.onPlayerLost(p);
            }
        }

        if (reason == null) {
            int cntNotLost = Iterables.size(Iterables.filter(game.getPlayers(), Player.Predicates.NOT_LOST));
            if (cntNotLost == 1) {
                reason = GameEndReason.AllOpponentsLost;
            }
            else if (cntNotLost == 0) {
                reason = GameEndReason.Draw;
            }
            else {
                return;
            }
        }

        // Clear Simultaneous triggers at the end of the game
        game.setGameOver(reason);
        game.getStack().clearSimultaneousStack();
    }

    /**
     * <p>
     * destroyPlaneswalkers.
     * </p>
     */
    private boolean handlePlaneswalkerRule(Player p) {
        // get all Planeswalkers
        final List<Card> list = CardLists.filter(p.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.PLANEWALKERS);

        boolean recheck = false;
        final Multimap<String, Card> uniqueWalkers = ArrayListMultimap.create();
        for (Card c : list) {
            if (c.getCounters(CounterType.LOYALTY) <= 0) {
                moveToGraveyard(c);
                // Play the Destroy sound
                game.fireEvent(new GameEventCardDestroyed());
                recheck = true;
            }


            for (final String type : c.getType()) {
                if (CardType.isAPlaneswalkerType(type)) {
                    uniqueWalkers.put(type, c);
                }
            }
        }

        for (String key : uniqueWalkers.keySet()) {
            Collection<Card> duplicates = uniqueWalkers.get(key);
            if (duplicates.size() < 2) {
                continue;
            }

            recheck = true;

            Card toKeep = p.getController().chooseSingleCardForEffect(duplicates, new AbilitySub(ApiType.InternalLegendaryRule, null, null, null), "You have multiple planeswalkers of type \""+key+"\"in play.\n\nChoose one to stay on battlefield (the rest will be moved to graveyard)");
            for (Card c: duplicates) {
                if (c != toKeep) {
                    moveToGraveyard(c);
                }
            }
        }
        return recheck;
    }

    /**
     * <p>
     * destroyLegendaryCreatures.
     * </p>
     */
    private boolean handleLegendRule(Player p, boolean yama) {
        final List<Card> a = CardLists.getType(p.getCardsIn(ZoneType.Battlefield), "Legendary");
        if (a.isEmpty() || game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.noLegendRule)) {
            return false;
        }
        boolean recheck = false;
        if (yama) {
            List<Card> yamazaki = CardLists.filter(a, CardPredicates.nameEquals("Brothers Yamazaki"));
            a.removeAll(yamazaki);
        }

        Multimap<String, Card> uniqueLegends = ArrayListMultimap.create();
        for (Card c : a) {
            if (!c.isFaceDown()) {
                uniqueLegends.put(c.getName(), c);
            }
        }

        for (String name : uniqueLegends.keySet()) {
            Collection<Card> cc = uniqueLegends.get(name);
            if (cc.size() < 2) {
                continue;
            }

            recheck = true;

            Card toKeep = p.getController().chooseSingleCardForEffect(cc, new AbilitySub(ApiType.InternalLegendaryRule, null, null, null), "You have multiple legendary permanents named \""+name+"\" in play.\n\nChoose the one to stay on battlefield (the rest will be moved to graveyard)");
            for (Card c: cc) {
                if (c != toKeep) {
                    sacrificeDestroy(c);
                }
            }
            game.fireEvent(new GameEventCardDestroyed());
        }

        return recheck;
    } // destroyLegendaryCreatures()


    public final boolean sacrifice(final Card c, final SpellAbility source) {
        if (!c.canBeSacrificedBy(source)) {
            return false;
        }

        this.sacrificeDestroy(c);

        // Play the Sacrifice sound
        game.fireEvent(new GameEventCardSacrificed());

        // Run triggers
        final HashMap<String, Object> runParams = new HashMap<String, Object>();
        runParams.put("Card", c);
        runParams.put("Cause", source);
        game.getTriggerHandler().runTrigger(TriggerType.Sacrificed, runParams, false);
        return true;
    }

    /**
     * <p>
     * destroy.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public final boolean destroy(final Card c, final SpellAbility sa) {
        if (!c.canBeDestroyed()) {
            return false;
        }

        if (c.canBeShielded() && (!c.isCreature() || c.getNetDefense() > 0)
                && (c.getShield() > 0 || c.hasKeyword("If CARDNAME would be destroyed, regenerate it."))) {
            c.subtractShield();
            c.setDamage(0);
            c.tap();
            c.addRegeneratedThisTurn();
            if (game.getCombat() != null) {
                game.getCombat().removeFromCombat(c);
            }

            // Play the Regen sound
            game.fireEvent(new GameEventCardRegenerated());

            return false;
        }

        return this.destroyNoRegeneration(c, sa);
    }

    /**
     * <p>
     * destroyNoRegeneration.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public final boolean destroyNoRegeneration(final Card c, final SpellAbility sa) {
        Player activator = null;
        if (!c.canBeDestroyed()) {
            return false;
        }

        if (c.isEnchanted()) {
            for (Card e : c.getEnchantedBy()) {
                CardFactoryUtil.refreshTotemArmor(e);
            }
        }

        // Replacement effects
        final HashMap<String, Object> repRunParams = new HashMap<String, Object>();
        repRunParams.put("Event", "Destroy");
        repRunParams.put("Source", sa);
        repRunParams.put("Card", c);
        repRunParams.put("Affected", c);

        if (game.getReplacementHandler().run(repRunParams) != ReplacementResult.NotReplaced) {
            return false;
        }


        if (sa != null) {
            activator = sa.getActivatingPlayer();
        }

        // Play the Destroy sound
        game.fireEvent(new GameEventCardDestroyed());

        // Run triggers
        final HashMap<String, Object> runParams = new HashMap<String, Object>();
        runParams.put("Card", c);
        runParams.put("Causer", activator);
        game.getTriggerHandler().runTrigger(TriggerType.Destroyed, runParams, false);

        return this.sacrificeDestroy(c);
    }

    /**
     * <p>
     * addSuspendTriggers.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.card.Card} object.
     */
    private static Card addSuspendTriggers(final Card c) {
        if (c.getSVar("HasteFromSuspend").equals("True")) {
            return c;
        }
        c.setSVar("HasteFromSuspend", "True");

        final Command intoPlay = new Command() {
            private static final long serialVersionUID = -4514610171270596654L;

            @Override
            public void run() {
                if (c.isInPlay() && c.isCreature()) {
                    c.addExtrinsicKeyword("Haste");
                }
            } // execute()
        };

        c.addComesIntoPlayCommand(intoPlay);

        final Command loseControl = new Command() {
            private static final long serialVersionUID = -4514610171270596654L;

            @Override
            public void run() {
                if (c.getSVar("HasteFromSuspend").equals("True")) {
                    c.setSVar("HasteFromSuspend", "False");
                    c.removeExtrinsicKeyword("Haste");
                }
            } // execute()
        };

        c.addChangeControllerCommand(loseControl);
        c.addLeavesPlayCommand(loseControl);
        return c;
    }

    /**
     * <p>
     * sacrificeDestroy.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public final boolean sacrificeDestroy(final Card c) {
        if (!c.isInPlay()) {
            return false;
        }

        boolean persist = (c.hasKeyword("Persist") && c.getCounters(CounterType.M1M1) == 0) && !c.isToken();
        boolean undying = (c.hasKeyword("Undying") && c.getCounters(CounterType.P1P1) == 0) && !c.isToken();

        final Card newCard = this.moveToGraveyard(c);

        // don't trigger persist/undying if the dying has been replaced
        if (newCard == null || !newCard.isInZone(ZoneType.Graveyard)) {
            persist = false;
            undying = false;
        }

        // Destroy needs to be called with Last Known Information
        c.executeTrigger(ZCTrigger.DESTROY);

        // System.out.println("Card " + c.getName() +
        // " is getting sent to GY, and this turn it got damaged by: ");

        if (persist) {
            final Card persistCard = newCard;
            String effect = String.format("AB$ ChangeZone | Cost$ 0 | Defined$ CardUID_%d" +
            		" | Origin$ Graveyard | Destination$ Battlefield | WithCounters$ M1M1_1",
                    persistCard.getUniqueNumber());
            SpellAbility persistAb = AbilityFactory.getAbility(effect, c);
            persistAb.setTrigger(true);
            persistAb.setStackDescription(newCard.getName() + " - Returning from Persist");
            persistAb.setDescription(newCard.getName() + " - Returning from Persist");
            persistAb.setActivatingPlayer(c.getController());

            game.getStack().addSimultaneousStackEntry(persistAb);
        }

        if (undying) {
            final Card undyingCard = newCard;
            String effect = String.format("AB$ ChangeZone | Cost$ 0 | Defined$ CardUID_%d |" +
            		" Origin$ Graveyard | Destination$ Battlefield | WithCounters$ P1P1_1",
            		undyingCard.getUniqueNumber());
            SpellAbility undyingAb = AbilityFactory.getAbility(effect, c);
            undyingAb.setTrigger(true);
            undyingAb.setStackDescription(newCard.getName() + " - Returning from Undying");
            undyingAb.setDescription(newCard.getName() + " - Returning from Undying");
            undyingAb.setActivatingPlayer(c.getController());

            game.getStack().addSimultaneousStackEntry(undyingAb);
        }
        return true;
    } // sacrificeDestroy()

    public void reveal(List<Card> cards, Player cardOwner) {
        reveal(cards, cardOwner, true);
    }

    public void reveal(List<Card> cards, Player cardOwner, boolean dontRevealToOwner) {
        ZoneType zt = ZoneType.Hand;
        if (!cards.isEmpty() && game.getZoneOf(cards.get(0)) != null) {
            zt = game.getZoneOf(cards.get(0)).getZoneType();
        }
        reveal(cardOwner + " reveals card from " + zt, cards, zt, cardOwner, dontRevealToOwner);
    }

    public void reveal(String message, List<Card> cards, Player cardOwner, boolean dontRevealToOwner) {
        if (cards.isEmpty()) {
            return;
        }
        reveal(message, cards, cards.get(0).getZone().getZoneType(), cardOwner, dontRevealToOwner);
    }

    public void reveal(String message, List<Card> cards, ZoneType zt, Player cardOwner, boolean dontRevealToOwner) {
        for (Player p : game.getPlayers()) {
            if (dontRevealToOwner && cardOwner == p) continue;
            p.getController().reveal(message, cards, zt, cardOwner);
        }
    }

    /** Delivers a message to all players. (use reveal to show Cards) */
    public void nofityOfValue(SpellAbility saSource, GameObject relatedTarget, String value, Player playerExcept) {
        for (Player p : game.getPlayers()) {
            if (playerExcept == p) continue;
            p.getController().notifyOfValue(saSource, relatedTarget, value);
        }
    }

    public void startGame(GameOutcome lastGameOutcome) {
        Player first = determineFirstTurnPlayer(lastGameOutcome);

        do {
            if (game.isGameOver()) break; // conceded during "play or draw"

            // FControl should determine now if there are any human players.
            // Where there are none, it should bring up speed controls
            game.fireEvent(new GameEventGameStarted(game.getType(), first, game.getPlayers()));

            game.setAge(GameStage.Mulligan);
            for (final Player p1 : game.getPlayers())
                p1.drawCards(p1.getMaxHandSize());

            performMulligans(first, game.getType() == GameType.Commander);
            if (game.isGameOver()) break; // conceded during "mulligan" prompt

            game.setAge(GameStage.Play);

            // THIS CODE WILL WORK WITH PHASE = NULL {
                if (game.getType() == GameType.Planechase) {
                    first.initPlane();
                }

                runOpeningHandActions(first);
                checkStateEffects(); // why?

                // Run Trigger beginning of the game
                final HashMap<String, Object> runParams = new HashMap<String, Object>();
                game.getTriggerHandler().runTrigger(TriggerType.NewGame, runParams, false);
            // }

            game.getPhaseHandler().startFirstTurn(first);

            first = game.getPhaseHandler().getPlayerTurn();  // needed only for restart
        } while (game.getAge() == GameStage.RestartedByKarn);

        // will pull UI dialog, when the UI is listening
        game.fireEvent(new GameEventGameFinished());
    }

    private Player determineFirstTurnPlayer(final GameOutcome lastGameOutcome) {
        // Only cut/coin toss if it's the first game of the match
        Player goesFirst = null;

        // 904.6: in Archenemy games the Archenemy goes first
        if (game != null && game.getType() == GameType.Archenemy) {
            for (Player p : game.getPlayers()) {
                if (p.isArchenemy()) {
                    return p;
                }
            }
        }

        boolean isFirstGame = lastGameOutcome == null;
        if (isFirstGame) {
            game.fireEvent(new GameEventFlipCoin()); // Play the Flip Coin sound
            goesFirst = Aggregates.random(game.getPlayers());
        } else {
            for (Player p : game.getPlayers()) {
                if (!lastGameOutcome.isWinner(p.getLobbyPlayer())) {
                    goesFirst = p;
                    break;
                }
            }
        }

        if (goesFirst == null) {
            // This happens in hotseat matches when 2 equal lobbyplayers play.
            // Noone of them has lost, so cannot decide who goes first .
            goesFirst = game.getPlayers().get(0); // does not really matter who plays first - it's controlled from the same computer.
        }

        boolean willPlay = goesFirst.getController().getWillPlayOnFirstTurn(isFirstGame);
        goesFirst = willPlay ? goesFirst : goesFirst.getOpponent();
        return goesFirst;
    }

    private void performMulligans(final Player firstPlayer, final boolean isCommander) {
        List<Player> whoCanMulligan = Lists.newArrayList(game.getPlayers());
        int offset = whoCanMulligan.indexOf(firstPlayer);

        // Have to cycle-shift the list to get the first player on index 0
        for (int i = 0; i < offset; i++) {
            whoCanMulligan.add(whoCanMulligan.remove(0));
        }

        boolean[] hasKept = new boolean[whoCanMulligan.size()];
        int[] handSize = new int[whoCanMulligan.size()];
        for (int i = 0; i < whoCanMulligan.size(); i++) {
            hasKept[i] = false;
            handSize[i] = whoCanMulligan.get(i).getZone(ZoneType.Hand).size();
        }

        MapOfLists<Player, Card> exiledDuringMulligans = new HashMapOfLists<Player, Card>(CollectionSuppliers.<Card>arrayLists());

        // rule 103.4b
        boolean isMultiPlayer = game.getPlayers().size() > 2;
        int mulliganDelta = isMultiPlayer ? 0 : 1;

        boolean allKept;
        do {
            allKept = true;
            for (int i = 0; i < whoCanMulligan.size(); i++) {
                if (hasKept[i]) continue;

                Player p = whoCanMulligan.get(i);
                List<Card> toMulligan = p.canMulligan() ? p.getController().getCardsToMulligan(isCommander, firstPlayer) : null;

                if (game.isGameOver()) // conceded on mulligan prompt
                    return;

                if (toMulligan != null && !toMulligan.isEmpty()) {
                    if (!isCommander) {
                        toMulligan = new ArrayList<Card>(p.getCardsIn(ZoneType.Hand));
                        for (final Card c : toMulligan) {
                            moveToLibrary(c);
                        }
                        p.shuffle(null);
                        p.drawCards(handSize[i] - mulliganDelta);
                    } else {
                        List<Card> toExile = Lists.newArrayList(toMulligan);
                        for (Card c : toExile) {
                            exile(c);
                        }
                        exiledDuringMulligans.addAll(p, toExile);
                        p.drawCards(toExile.size() - 1);
                    }

                    p.onMulliganned();
                    allKept = false;
                } else {
                    game.getGameLog().add(GameLogEntryType.MULLIGAN, p.getName() + " has kept a hand of " + p.getZone(ZoneType.Hand).size() + " cards");
                    hasKept[i] = true;
                }
            }
            mulliganDelta++;
        } while (!allKept);

        if (isCommander) {
            for (Entry<Player, Collection<Card>> kv : exiledDuringMulligans.entrySet()) {
                Player p = kv.getKey();
                Collection<Card> cc = kv.getValue();
                for (Card c : cc) {
                    moveToLibrary(c);
                }
                p.shuffle(null);
            }
        }
    }

    private void runOpeningHandActions(final Player first) {
        Player takesAction = first;
        do {
            List<SpellAbility> usableFromOpeningHand = new ArrayList<SpellAbility>();

            // Select what can be activated from a given hand
            for (final Card c : takesAction.getCardsIn(ZoneType.Hand)) {
                for (String kw : c.getKeyword()) {
                    if (kw.startsWith("MayEffectFromOpeningHand")) {
                        String[] split = kw.split(":");
                        final String effName = split[1];
                        if (split.length > 2 && split[2].equalsIgnoreCase("!PlayFirst") && first == takesAction) {
                            continue;
                        }

                        final SpellAbility effect = AbilityFactory.getAbility(c.getSVar(effName), c);
                        effect.setActivatingPlayer(takesAction);

                        usableFromOpeningHand.add(effect);
                    }
                }
            }

            // Players are supposed to return the effects in an order they want those to be resolved (Rule 103.5)
            if (!usableFromOpeningHand.isEmpty()) {
                usableFromOpeningHand = takesAction.getController().chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
            }

            for (final SpellAbility sa : usableFromOpeningHand) {
                if (!takesAction.getZone(ZoneType.Hand).contains(sa.getSourceCard())) {
                    continue;
                }

                takesAction.getController().playSpellAbilityNoStack(sa, true);
            }
            takesAction = game.getNextPlayerAfter(takesAction);
        } while (takesAction != first);
        // state effects are checked only when someone gets priority
    }

    // Invokes given runnable in Game thread pool - used to start game and perform actions from UI (when game-0 waits for input)
    public void invoke(final Runnable proc) {
        if (ThreadUtil.isGameThread()) {
            proc.run();
        }
        else {
            ThreadUtil.invokeInGameThread(proc);
        }
    }
}
