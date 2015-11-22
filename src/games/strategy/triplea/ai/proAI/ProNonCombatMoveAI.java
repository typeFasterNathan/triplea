package games.strategy.triplea.ai.proAI;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.ai.proAI.logging.ProLogger;
import games.strategy.triplea.ai.proAI.util.ProAttackOptionsUtils;
import games.strategy.triplea.ai.proAI.util.ProBattleUtils;
import games.strategy.triplea.ai.proAI.util.ProMatches;
import games.strategy.triplea.ai.proAI.util.ProMoveUtils;
import games.strategy.triplea.ai.proAI.util.ProPurchaseUtils;
import games.strategy.triplea.ai.proAI.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.proAI.util.ProTransportUtils;
import games.strategy.triplea.ai.proAI.util.ProUtils;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.attatchments.UnitAttachment;
import games.strategy.triplea.delegate.AbstractMoveDelegate;
import games.strategy.triplea.delegate.BattleCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveValidator;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.dataObjects.MoveValidationResult;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.util.CompositeMatchAnd;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pro non-combat move AI.
 */
public class ProNonCombatMoveAI {

  public static double WIN_PERCENTAGE = 95;
  public static double MIN_WIN_PERCENTAGE = 75;

  // Utilities
  private final ProUtils utils;
  private final ProBattleUtils battleUtils;
  private final ProTransportUtils transportUtils;
  private final ProAttackOptionsUtils attackOptionsUtils;
  private final ProMoveUtils moveUtils;
  private final ProTerritoryValueUtils territoryValueUtils;
  private final ProPurchaseUtils purchaseUtils;

  // Current map settings
  private boolean areNeutralsPassableByAir;

  // Current data
  private GameData data;
  private PlayerID player;
  private Territory myCapital;
  private List<Territory> allTerritories;
  private Map<Unit, Territory> unitTerritoryMap;
  private IntegerMap<UnitType> playerCostMap;
  private double minCostPerHitPoint;

  public ProNonCombatMoveAI(final ProUtils utils, final ProBattleUtils battleUtils,
      final ProTransportUtils transportUtils, final ProAttackOptionsUtils attackOptionsUtils,
      final ProMoveUtils moveUtils, final ProTerritoryValueUtils territoryValueUtils,
      final ProPurchaseUtils purchaseUtils) {
    this.utils = utils;
    this.battleUtils = battleUtils;
    this.transportUtils = transportUtils;
    this.attackOptionsUtils = attackOptionsUtils;
    this.moveUtils = moveUtils;
    this.territoryValueUtils = territoryValueUtils;
    this.purchaseUtils = purchaseUtils;
  }

  public Map<Territory, ProAttackTerritoryData> doNonCombatMove(Map<Territory, ProAttackTerritoryData> factoryMoveMap,
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final IMoveDelegate moveDel, final GameData data,
      final PlayerID player, final boolean isSimulation) {
    ProLogger.info("Starting non-combat move phase");

    // Current data at the start of combat move
    this.data = data;
    this.player = player;
    areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));
    myCapital = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    allTerritories = data.getMap().getTerritories();
    unitTerritoryMap = createUnitTerritoryMap(player);
    playerCostMap = BattleCalculator.getCostsForTUV(player, data);
    if (!games.strategy.triplea.Properties.getLow_Luck(data)) // Set optimal and min win percentage lower if not LL
    {
      WIN_PERCENTAGE = 90;
      MIN_WIN_PERCENTAGE = 65;
    }

    // Initialize data containers
    Map<Territory, ProAttackTerritoryData> moveMap = new HashMap<Territory, ProAttackTerritoryData>();
    Map<Unit, Set<Territory>> unitMoveMap = new HashMap<Unit, Set<Territory>>();
    Map<Unit, Set<Territory>> transportMoveMap = new HashMap<Unit, Set<Territory>>();
    List<ProAmphibData> transportMapList = new ArrayList<ProAmphibData>();
    final Map<Territory, Set<Territory>> landRoutesMap = new HashMap<Territory, Set<Territory>>();
    final Map<Territory, ProAttackTerritoryData> enemyAttackMap = new HashMap<Territory, ProAttackTerritoryData>();
    Map<Unit, Set<Territory>> infraUnitMoveMap = new HashMap<Unit, Set<Territory>>();
    List<ProAttackTerritoryData> prioritizedTerritories = new ArrayList<ProAttackTerritoryData>();

    // Find all purchase options
    final List<ProPurchaseOption> specialPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> factoryPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> landPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> airPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> seaPurchaseOptions = new ArrayList<ProPurchaseOption>();
    purchaseUtils.findPurchaseOptions(player, landPurchaseOptions, airPurchaseOptions, seaPurchaseOptions,
        factoryPurchaseOptions, specialPurchaseOptions);
    minCostPerHitPoint = purchaseUtils.getMinCostPerHitPoint(player, landPurchaseOptions);

    // Find the max number of units that can move to each allied territory
    final Match<Territory> myUnitTerritoriesMatch =
        Matches.territoryHasUnitsThatMatch(ProMatches.unitCanBeMovedAndIsOwned(player));
    final List<Territory> myUnitTerritories = Match.getMatches(allTerritories, myUnitTerritoriesMatch);
    attackOptionsUtils.findDefendOptions(player, myUnitTerritories, moveMap, unitMoveMap, transportMoveMap,
        landRoutesMap, transportMapList, new ArrayList<Territory>());

    // Find number of units in each move territory that can't move and all infra units
    findUnitsThatCantMove(moveMap, unitMoveMap, purchaseTerritories, landPurchaseOptions, transportMapList);
    infraUnitMoveMap = findInfraUnitsThatCanMove(unitMoveMap);

    // Try to have one land unit in each territory that is bordering an enemy territory
    final List<Territory> movedOneDefenderToTerritories =
        moveOneDefenderToLandTerritoriesBorderingEnemy(moveMap, unitMoveMap);

    // Determine max enemy attack units and if territories can be held
    attackOptionsUtils.findEnemyAttackOptions(player, movedOneDefenderToTerritories,
        new ArrayList<Territory>(moveMap.keySet()), enemyAttackMap);
    determineIfMoveTerritoriesCanBeHeld(moveMap, enemyAttackMap);

    // Get list of territories that can't be held and find move value for each territory
    final List<Territory> territoriesThatCantBeHeld = new ArrayList<Territory>();
    for (final Territory t : moveMap.keySet()) {
      if (!moveMap.get(t).isCanHold()) {
        territoriesThatCantBeHeld.add(t);
      }
    }
    final Map<Territory, Double> territoryValueMap =
        territoryValueUtils.findTerritoryValues(player, minCostPerHitPoint, territoriesThatCantBeHeld,
            new ArrayList<Territory>());
    final Map<Territory, Double> seaTerritoryValueMap =
        territoryValueUtils.findSeaTerritoryValues(player, territoriesThatCantBeHeld);

    // Prioritize territories to defend
    prioritizedTerritories = prioritizeDefendOptions(moveMap, factoryMoveMap, territoryValueMap, enemyAttackMap);

    // Determine which territories to defend and how many units each one needs
    final int enemyDistance = utils.getClosestEnemyLandTerritoryDistance(data, player, myCapital);
    moveUnitsToDefendTerritories(moveMap, unitMoveMap, prioritizedTerritories, transportMapList, transportMoveMap,
        enemyDistance, territoryValueMap);

    // Copy data in case capital defense needs increased
    final Map<Territory, ProAttackTerritoryData> tempMoveMap = new HashMap<Territory, ProAttackTerritoryData>();
    for (final Territory t : moveMap.keySet()) {
      tempMoveMap.put(t, new ProAttackTerritoryData(moveMap.get(t)));
    }
    final Map<Unit, Set<Territory>> tempUnitMoveMap = new HashMap<Unit, Set<Territory>>(unitMoveMap);
    final Map<Unit, Set<Territory>> tempTransportMoveMap = new HashMap<Unit, Set<Territory>>(transportMoveMap);
    final List<ProAmphibData> tempTransportMapList = new ArrayList<ProAmphibData>(transportMapList);

    // Use loop to ensure capital is protected after moves
    if (myCapital != null) {
      int defenseRange = -1;
      while (true) {

        // Add value to territories near capital if necessary
        for (final Territory t : moveMap.keySet()) {
          double value = territoryValueMap.get(t);
          final int distance =
              data.getMap().getDistance(myCapital, t, ProMatches.territoryCanMoveLandUnits(player, data, false));
          if (distance >= 0 && distance <= defenseRange) {
            value *= 10;
          }
          moveMap.get(t).setValue(value);
          if (t.isWater()) {
            moveMap.get(t).setSeaValue(seaTerritoryValueMap.get(t));
          }
        }

        // Move units to best territories
        moveUnitsToBestTerritories(moveMap, unitMoveMap, transportMapList, transportMoveMap);

        // Check if capital has local land superiority
        ProLogger.info("Checking if capital has local land superiority with enemyDistance=" + enemyDistance);
        if (enemyDistance >= 2 && enemyDistance <= 3 && defenseRange == -1
            && !battleUtils.territoryHasLocalLandSuperiorityAfterMoves(myCapital, enemyDistance, player, moveMap)) {
          defenseRange = enemyDistance - 1;
          moveMap = tempMoveMap;
          unitMoveMap = tempUnitMoveMap;
          transportMoveMap = tempTransportMoveMap;
          transportMapList = tempTransportMapList;
          ProLogger.debug("Capital doesn't have local land superiority so setting defensive stance");
        } else {
          break;
        }
      }
    }

    // Determine where to move infra units
    factoryMoveMap = moveInfraUnits(factoryMoveMap, moveMap, infraUnitMoveMap);

    // Log a warning if any units not assigned to a territory (skip infrastructure for now)
    for (final Unit u : unitMoveMap.keySet()) {
      if (Matches.UnitIsInfrastructure.invert().match(u)) {
        ProLogger.warn(player + ": " + unitTerritoryMap.get(u) + " has unmoved unit: " + u + " with options: "
            + unitMoveMap.get(u));
      }
    }

    // Calculate move routes and perform moves
    doMove(moveMap, moveDel, data, player, isSimulation);

    // Log results
    ProLogger.info("Logging results");
    logAttackMoves(moveMap, unitMoveMap, transportMapList, prioritizedTerritories, enemyAttackMap);
    return factoryMoveMap;
  }

  public void doMove(final Map<Territory, ProAttackTerritoryData> moveMap, final IMoveDelegate moveDel,
      final GameData data, final PlayerID player, final boolean isSimulation) {

    this.data = data;
    this.player = player;
    areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));

    // Calculate move routes and perform moves
    final List<Collection<Unit>> moveUnits = new ArrayList<Collection<Unit>>();
    final List<Route> moveRoutes = new ArrayList<Route>();
    moveUtils.calculateMoveRoutes(player, areNeutralsPassableByAir, moveUnits, moveRoutes, moveMap, false);
    moveUtils.doMove(moveUnits, moveRoutes, null, moveDel, isSimulation);

    // Calculate amphib move routes and perform moves
    moveUnits.clear();
    moveRoutes.clear();
    final List<Collection<Unit>> transportsToLoad = new ArrayList<Collection<Unit>>();
    moveUtils.calculateAmphibRoutes(player, moveUnits, moveRoutes, transportsToLoad, moveMap, false);
    moveUtils.doMove(moveUnits, moveRoutes, transportsToLoad, moveDel, isSimulation);
  }

  private void findUnitsThatCantMove(final Map<Territory, ProAttackTerritoryData> moveMap,
      final Map<Unit, Set<Territory>> unitMoveMap, final Map<Territory, ProPurchaseTerritory> purchaseTerritories,
      final List<ProPurchaseOption> landPurchaseOptions, final List<ProAmphibData> transportMapList) {

    ProLogger.info("Find units that can't move");

    // Add all units that can't move (allied units, 0 move units, etc)
    for (final Territory t : moveMap.keySet()) {
      moveMap.get(t).getCantMoveUnits()
          .addAll(t.getUnits().getMatches(ProMatches.unitCantBeMovedAndIsAlliedDefender(player, data, t)));
    }

    // Add all units that only have 1 move option and can't be transported
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (unitMoveMap.get(u).size() == 1) {
        final Territory onlyTerritory = unitMoveMap.get(u).iterator().next();
        if (onlyTerritory.equals(unitTerritoryMap.get(u))) {
          boolean canBeTransported = false;
          for (final ProAmphibData pad : transportMapList) {
            for (final Territory t : pad.getTransportMap().keySet()) {
              if (pad.getTransportMap().get(t).contains(onlyTerritory)) {
                canBeTransported = true;
              }
            }
            for (final Territory t : pad.getSeaTransportMap().keySet()) {
              if (pad.getSeaTransportMap().get(t).contains(onlyTerritory)) {
                canBeTransported = true;
              }
            }
          }
          if (!canBeTransported) {
            moveMap.get(onlyTerritory).getCantMoveUnits().add(u);
            it.remove();
          }
        }
      }
    }

    // Check if purchase units are known yet
    if (purchaseTerritories != null) {

      // Add all units that will be purchased
      for (final ProPurchaseTerritory ppt : purchaseTerritories.values()) {
        for (final ProPlaceTerritory placeTerritory : ppt.getCanPlaceTerritories()) {
          final Territory t = placeTerritory.getTerritory();
          if (moveMap.get(t) != null) {
            moveMap.get(t).getCantMoveUnits().addAll(placeTerritory.getPlaceUnits());
          }
        }
      }
    } else {

      // Add max defenders that can be purchased to each territory
      for (final Territory t : moveMap.keySet()) {
        if (ProMatches.territoryHasInfraFactoryAndIsNotConqueredOwnedLand(player, data).match(t)) {
          moveMap.get(t).getCantMoveUnits()
              .addAll(purchaseUtils.findMaxPurchaseDefenders(player, t, landPurchaseOptions));
        }
      }
    }

    // Log can't move units per territory
    for (final Territory t : moveMap.keySet()) {
      if (!moveMap.get(t).getCantMoveUnits().isEmpty()) {
        ProLogger.trace(t + " has units that can't move: " + moveMap.get(t).getCantMoveUnits());
      }
    }
  }

  private Map<Unit, Set<Territory>> findInfraUnitsThatCanMove(final Map<Unit, Set<Territory>> unitMoveMap) {

    ProLogger.info("Find non-combat infra units that can move");

    // Add all units that are infra
    final Map<Unit, Set<Territory>> infraUnitMoveMap = new HashMap<Unit, Set<Territory>>();
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (ProMatches.unitCanBeMovedAndIsOwnedNonCombatInfra(player).match(u)) {
        infraUnitMoveMap.put(u, unitMoveMap.get(u));
        ProLogger.trace(u + " is infra unit with move options: " + unitMoveMap.get(u));
        it.remove();
      }
    }
    return infraUnitMoveMap;
  }

  private List<Territory> moveOneDefenderToLandTerritoriesBorderingEnemy(
      final Map<Territory, ProAttackTerritoryData> moveMap, final Map<Unit, Set<Territory>> unitMoveMap) {

    ProLogger.info("Determine which territories to defend with one land unit");

    // Find land territories with no can't move units and adjacent to enemy land units
    final List<Territory> territoriesToDefendWithOneUnit = new ArrayList<Territory>();
    for (final Territory t : moveMap.keySet()) {
      final boolean hasAlliedLandUnits =
          Match.someMatch(moveMap.get(t).getCantMoveUnits(), ProMatches.unitIsAlliedLandAndNotInfra(player, data));
      if (!t.isWater()
          && !hasAlliedLandUnits
          && ProMatches.territoryHasNeighborOwnedByAndHasLandUnit(data, player, utils.getPotentialEnemyPlayers(player))
              .match(t)) {
        territoriesToDefendWithOneUnit.add(t);
      }
    }
    final List<Territory> result = new ArrayList<Territory>(territoriesToDefendWithOneUnit);

    // Sort units by number of defend options and cost
    final Map<Unit, Set<Territory>> sortedUnitMoveOptions = attackOptionsUtils.sortUnitMoveOptions(player, unitMoveMap);

    // Set unit with the fewest move options in each territory
    for (final Unit unit : sortedUnitMoveOptions.keySet()) {
      if (Matches.UnitIsLand.match(unit)) {
        for (final Territory t : sortedUnitMoveOptions.get(unit)) {
          final int unitValue = playerCostMap.getInt(unit.getType());
          int production = 0;
          final TerritoryAttachment ta = TerritoryAttachment.get(t);
          if (ta != null) {
            production = ta.getProduction();
          }
          if (territoriesToDefendWithOneUnit.contains(t) && unitValue <= (production + 3)) {
            moveMap.get(t).addUnit(unit);
            unitMoveMap.remove(unit);
            territoriesToDefendWithOneUnit.remove(t);
            ProLogger.debug(t + ", added one land unit: " + unit);
            break;
          }
        }
        if (territoriesToDefendWithOneUnit.isEmpty()) {
          break;
        }
      }
    }
    return result;
  }

  private void determineIfMoveTerritoriesCanBeHeld(final Map<Territory, ProAttackTerritoryData> moveMap,
      final Map<Territory, ProAttackTerritoryData> enemyAttackMap) {

    ProLogger.info("Find max enemy attackers and if territories can be held");

    // Determine which territories can possibly be held
    final IntegerMap<UnitType> playerCostMap = BattleCalculator.getCostsForTUV(player, data);
    for (final Territory t : moveMap.keySet()) {
      final ProAttackTerritoryData patd = moveMap.get(t);

      // Check if no enemy attackers
      if (enemyAttackMap.get(t) == null) {
        ProLogger.debug("Territory=" + t.getName() + ", CanHold=true since has no enemy attackers");
        continue;
      }

      // Check if min defenders can hold it (not considering AA)
      final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackMap.get(t).getMaxUnits());
      enemyAttackingUnits.addAll(enemyAttackMap.get(t).getMaxAmphibUnits());
      patd.setMaxEnemyUnits(new ArrayList<Unit>(enemyAttackingUnits));
      patd.setMaxEnemyBombardUnits(enemyAttackMap.get(t).getMaxBombardUnits());
      final List<Unit> minDefendingUnitsAndNotAA =
          Match.getMatches(patd.getCantMoveUnits(), Matches.UnitIsAAforAnything.invert());
      final ProBattleResultData minResult =
          battleUtils.calculateBattleResults(player, t, new ArrayList<Unit>(enemyAttackingUnits),
              minDefendingUnitsAndNotAA, enemyAttackMap.get(t).getMaxBombardUnits(), false);
      patd.setMinBattleResult(minResult);
      if (minResult.getTUVSwing() <= 0 && !minDefendingUnitsAndNotAA.isEmpty()) {
        ProLogger.debug("Territory=" + t.getName() + ", CanHold=true" + ", MinDefenders="
            + minDefendingUnitsAndNotAA.size() + ", EnemyAttackers=" + enemyAttackingUnits.size() + ", win%="
            + minResult.getWinPercentage() + ", EnemyTUVSwing=" + minResult.getTUVSwing() + ", hasLandUnitRemaining="
            + minResult.isHasLandUnitRemaining());
        continue;
      }

      // Check if max defenders can hold it (not considering AA)
      final Set<Unit> defendingUnits = new HashSet<Unit>(patd.getMaxUnits());
      defendingUnits.addAll(patd.getMaxAmphibUnits());
      defendingUnits.addAll(patd.getCantMoveUnits());
      final List<Unit> defendingUnitsAndNotAA = Match.getMatches(defendingUnits, Matches.UnitIsAAforAnything.invert());
      final ProBattleResultData result =
          battleUtils.calculateBattleResults(player, t, new ArrayList<Unit>(enemyAttackingUnits),
              defendingUnitsAndNotAA, enemyAttackMap.get(t).getMaxBombardUnits(), false);
      int isFactory = 0;
      if (ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t)) {
        isFactory = 1;
      }
      int isMyCapital = 0;
      if (t.equals(myCapital)) {
        isMyCapital = 1;
      }
      final List<Unit> extraUnits = new ArrayList<Unit>(defendingUnitsAndNotAA);
      extraUnits.removeAll(minDefendingUnitsAndNotAA);
      final double extraUnitValue = BattleCalculator.getTUV(extraUnits, playerCostMap);
      final double holdValue = extraUnitValue / 8 * (1 + 0.5 * isFactory) * (1 + 2 * isMyCapital);
      if (minDefendingUnitsAndNotAA.size() != defendingUnitsAndNotAA.size()
          && (result.getTUVSwing() - holdValue) < minResult.getTUVSwing()) {
        ProLogger.debug("Territory=" + t.getName() + ", CanHold=true" + ", MaxDefenders="
            + defendingUnitsAndNotAA.size() + ", EnemyAttackers=" + enemyAttackingUnits.size() + ", minTUVSwing="
            + minResult.getTUVSwing() + ", win%=" + result.getWinPercentage() + ", EnemyTUVSwing="
            + result.getTUVSwing() + ", hasLandUnitRemaining=" + result.isHasLandUnitRemaining() + ", holdValue="
            + holdValue);
        continue;
      }

      // Can't hold territory
      patd.setCanHold(false);
      ProLogger.debug("Can't hold Territory=" + t.getName() + ", MaxDefenders=" + defendingUnitsAndNotAA.size()
          + ", EnemyAttackers=" + enemyAttackingUnits.size() + ", minTUVSwing=" + minResult.getTUVSwing() + ", win%="
          + result.getWinPercentage() + ", EnemyTUVSwing=" + result.getTUVSwing() + ", hasLandUnitRemaining="
          + result.isHasLandUnitRemaining() + ", holdValue=" + holdValue);
    }
  }

  private List<ProAttackTerritoryData> prioritizeDefendOptions(final Map<Territory, ProAttackTerritoryData> moveMap,
      final Map<Territory, ProAttackTerritoryData> factoryMoveMap, final Map<Territory, Double> territoryValueMap,
      final Map<Territory, ProAttackTerritoryData> enemyAttackMap) {

    ProLogger.info("Prioritizing territories to try to defend");

    // Calculate value of defending territory
    for (final Territory t : moveMap.keySet()) {

      // Determine if it is my capital or adjacent to my capital
      int isMyCapital = 0;
      if (t.equals(myCapital)) {
        isMyCapital = 1;
      }

      // Determine if it has a factory
      int isFactory = 0;
      if (ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t)
          || (factoryMoveMap != null && factoryMoveMap.containsKey(t))) {
        isFactory = 1;
      }

      // Determine production value and if it is an enemy capital
      int production = 0;
      int isEnemyOrAlliedCapital = 0;
      final TerritoryAttachment ta = TerritoryAttachment.get(t);
      if (ta != null) {
        production = ta.getProduction();
        if (ta.isCapital() && !t.equals(myCapital)) {
          isEnemyOrAlliedCapital = 1;
        }
      }

      // Determine neighbor value
      double neighborValue = 0;
      if (!t.isWater()) {
        final Set<Territory> landNeighbors = data.getMap().getNeighbors(t, Matches.TerritoryIsLand);
        for (final Territory neighbor : landNeighbors) {
          double neighborProduction = TerritoryAttachment.getProduction(neighbor);
          if (Matches.isTerritoryAllied(player, data).match(neighbor)) {
            neighborProduction = 0.1 * neighborProduction;
          }
          neighborValue += neighborProduction;
        }
      }

      // Determine defending unit value
      final int cantMoveUnitValue = BattleCalculator.getTUV(moveMap.get(t).getCantMoveUnits(), playerCostMap);
      double unitOwnerMultiplier = 1;
      if (Match.noneMatch(moveMap.get(t).getCantMoveUnits(), Matches.unitIsOwnedBy(player))) {
        if (t.isWater()
            && Match.noneMatch(moveMap.get(t).getCantMoveUnits(), Matches.UnitIsTransportButNotCombatTransport)) {
          unitOwnerMultiplier = 0;
        } else {
          unitOwnerMultiplier = 0.5;
        }
      }

      // Calculate defense value for prioritization
      final double territoryValue =
          unitOwnerMultiplier * (2 * production + 10 * isFactory + 0.5 * cantMoveUnitValue + 0.5 * neighborValue)
              * (1 + 10 * isMyCapital) * (1 + 4 * isEnemyOrAlliedCapital);
      moveMap.get(t).setValue(territoryValue);
    }

    // Sort attack territories by value
    final List<ProAttackTerritoryData> prioritizedTerritories = new ArrayList<ProAttackTerritoryData>(moveMap.values());
    Collections.sort(prioritizedTerritories, new Comparator<ProAttackTerritoryData>() {
      @Override
      public int compare(final ProAttackTerritoryData t1, final ProAttackTerritoryData t2) {
        final double value1 = t1.getValue();
        final double value2 = t2.getValue();
        return Double.compare(value2, value1);
      }
    });

    // Remove territories that I'm not going to try to defend
    for (final Iterator<ProAttackTerritoryData> it = prioritizedTerritories.iterator(); it.hasNext();) {
      final ProAttackTerritoryData patd = it.next();
      final Territory t = patd.getTerritory();
      final boolean hasFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t);
      final ProBattleResultData minResult = patd.getMinBattleResult();
      final int cantMoveUnitValue = BattleCalculator.getTUV(moveMap.get(t).getCantMoveUnits(), playerCostMap);
      final boolean isLandAndCanOnlyBeAttackedByAir =
          !t.isWater() && Match.allMatch(patd.getMaxEnemyUnits(), Matches.UnitIsAir);
      final boolean isNotFactoryAndShouldHold =
          !hasFactory && (minResult.getTUVSwing() <= 0 || !minResult.isHasLandUnitRemaining());
      final boolean canAlreadyBeHeld =
          minResult.getTUVSwing() <= 0 && minResult.getWinPercentage() < (100 - WIN_PERCENTAGE);
      final boolean isNotFactoryAndHasNoEnemyNeighbors =
          !t.isWater()
              && !hasFactory
              && !ProMatches.territoryHasNeighborOwnedByAndHasLandUnit(data, player,
                  utils.getPotentialEnemyPlayers(player)).match(t);
      final boolean isNotFactoryAndOnlyAmphib =
          !t.isWater() && !hasFactory && Match.noneMatch(moveMap.get(t).getMaxUnits(), Matches.UnitIsLand)
              && cantMoveUnitValue < 5;
      if (!patd.isCanHold() || patd.getValue() <= 0 || isLandAndCanOnlyBeAttackedByAir || isNotFactoryAndShouldHold
          || canAlreadyBeHeld || isNotFactoryAndHasNoEnemyNeighbors || isNotFactoryAndOnlyAmphib) {
        final double TUVSwing = minResult.getTUVSwing();
        final boolean hasRemainingLandUnit = minResult.isHasLandUnitRemaining();
        ProLogger.debug("Removing territory=" + t.getName() + ", value=" + patd.getValue() + ", CanHold="
            + patd.isCanHold() + ", isLandAndCanOnlyBeAttackedByAir=" + isLandAndCanOnlyBeAttackedByAir
            + ", isNotFactoryAndShouldHold=" + isNotFactoryAndShouldHold + ", canAlreadyBeHeld=" + canAlreadyBeHeld
            + ", isNotFactoryAndHasNoEnemyNeighbors=" + isNotFactoryAndHasNoEnemyNeighbors
            + ", isNotFactoryAndOnlyAmphib=" + isNotFactoryAndOnlyAmphib + ", TUVSwing=" + TUVSwing
            + ", hasRemainingLandUnit=" + hasRemainingLandUnit + ", maxEnemyUnits=" + patd.getMaxEnemyUnits().size());
        it.remove();
      }
    }

    // Add best sea production territory for sea factories
    List<Territory> seaFactories =
        Match.getMatches(data.getMap().getTerritories(),
            ProMatches.territoryHasInfraFactoryAndIsNotConqueredOwnedLand(player, data));
    seaFactories =
        Match.getMatches(seaFactories, ProMatches.territoryHasInfraFactoryAndIsOwnedLandAdjacentToSea(player, data));
    for (final Territory t : seaFactories) {
      if (territoryValueMap.get(t) >= 1) {
        continue;
      }
      final Set<Territory> neighbors =
          data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnits(player, data, true));
      double maxValue = 0;
      Territory maxTerritory = null;
      for (final Territory neighbor : neighbors) {
        if (moveMap.get(neighbor) != null && moveMap.get(neighbor).isCanHold()
            && territoryValueMap.get(neighbor) > maxValue) {
          maxTerritory = neighbor;
          maxValue = territoryValueMap.get(neighbor);
        }
      }
      if (maxTerritory != null && enemyAttackMap.get(maxTerritory) != null) {
        boolean alreadyAdded = false;
        for (final ProAttackTerritoryData patd : prioritizedTerritories) {
          if (patd.getTerritory().equals(maxTerritory)) {
            alreadyAdded = true;
          }
        }
        if (!alreadyAdded) {
          prioritizedTerritories.add(moveMap.get(maxTerritory));
        }
      }
    }

    // Log prioritized territories
    for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories) {
      ProLogger.debug("Value=" + attackTerritoryData.getValue() + ", " + attackTerritoryData.getTerritory().getName());
    }
    return prioritizedTerritories;
  }

  private void moveUnitsToDefendTerritories(final Map<Territory, ProAttackTerritoryData> moveMap,
      final Map<Unit, Set<Territory>> unitMoveMap, final List<ProAttackTerritoryData> prioritizedTerritories,
      final List<ProAmphibData> transportMapList, final Map<Unit, Set<Territory>> transportMoveMap,
      final int enemyDistance, final Map<Territory, Double> territoryValueMap) {

    ProLogger.info("Determine units to defend territories with");
    if (prioritizedTerritories.isEmpty()) {
      return;
    }

    // Assign units to territories by prioritization
    final Map<Unit, Territory> unitTerritoryMap = utils.createUnitTerritoryMap(player);
    int numToDefend = 1;
    while (true) {

      // Reset lists
      for (final Territory t : moveMap.keySet()) {
        moveMap.get(t).getTempUnits().clear();
        moveMap.get(t).getTempAmphibAttackMap().clear();
        moveMap.get(t).getTransportTerritoryMap().clear();
        moveMap.get(t).setBattleResult(null);
      }

      // Determine number of territories to defend
      if (numToDefend <= 0) {
        break;
      }
      final List<ProAttackTerritoryData> territoriesToTryToDefend = prioritizedTerritories.subList(0, numToDefend);

      // Loop through all units and determine defend options
      final Map<Unit, Set<Territory>> unitDefendOptions = new HashMap<Unit, Set<Territory>>();
      for (final Unit unit : unitMoveMap.keySet()) {

        // Find number of move options
        final Set<Territory> canDefendTerritories = new LinkedHashSet<Territory>();
        for (final ProAttackTerritoryData attackTerritoryData : territoriesToTryToDefend) {
          if (unitMoveMap.get(unit).contains(attackTerritoryData.getTerritory())) {
            canDefendTerritories.add(attackTerritoryData.getTerritory());
          }
        }
        unitDefendOptions.put(unit, canDefendTerritories);
      }

      // Sort units by number of defend options and cost
      final Map<Unit, Set<Territory>> sortedUnitMoveOptions =
          attackOptionsUtils.sortUnitMoveOptions(player, unitDefendOptions);

      // Set enough units in territories to have at least a chance of winning
      for (final Iterator<Unit> it = sortedUnitMoveOptions.keySet().iterator(); it.hasNext();) {
        final Unit unit = it.next();
        final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
        if (isAirUnit || Matches.UnitIsCarrier.match(unit)) {
          continue; // skip air and carrier units
        }
        final TreeMap<Double, Territory> estimatesMap = new TreeMap<Double, Territory>();
        for (final Territory t : sortedUnitMoveOptions.get(unit)) {
          List<Unit> defendingUnits =
              Match.getMatches(moveMap.get(t).getAllDefenders(), ProMatches.unitIsAlliedNotOwnedAir(player, data)
                  .invert());
          if (t.isWater()) {
            defendingUnits = moveMap.get(t).getAllDefenders();
          }
          final double estimate =
              battleUtils.estimateStrengthDifference(t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits);
          estimatesMap.put(estimate, t);
        }
        if (!estimatesMap.isEmpty() && estimatesMap.lastKey() > 60) {
          final Territory minWinTerritory = estimatesMap.lastEntry().getValue();
          moveMap.get(minWinTerritory).addTempUnit(unit);
          it.remove();
        }
      }

      // Set non-air units in territories
      for (final Iterator<Unit> it = sortedUnitMoveOptions.keySet().iterator(); it.hasNext();) {
        final Unit unit = it.next();
        if (Matches.UnitCanLandOnCarrier.match(unit)) {
          continue;
        }
        Territory maxWinTerritory = null;
        double maxWinPercentage = -1;
        for (final Territory t : sortedUnitMoveOptions.get(unit)) {
          List<Unit> defendingUnits =
              Match.getMatches(moveMap.get(t).getAllDefenders(), ProMatches.unitIsAlliedNotOwnedAir(player, data)
                  .invert());
          if (t.isWater()) {
            defendingUnits = moveMap.get(t).getAllDefenders();
          }
          if (moveMap.get(t).getBattleResult() == null) {
            moveMap.get(t).setBattleResult(
                battleUtils.estimateDefendBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits,
                    moveMap.get(t).getMaxEnemyBombardUnits()));
          }
          final ProBattleResultData result = moveMap.get(t).getBattleResult();
          final boolean hasFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t);
          if (result.getWinPercentage() > maxWinPercentage
              && ((t.equals(myCapital) && result.getWinPercentage() > (100 - WIN_PERCENTAGE))
                  || (hasFactory && result.getWinPercentage() > (100 - MIN_WIN_PERCENTAGE)) || result.getTUVSwing() >= 0)) {
            maxWinTerritory = t;
            maxWinPercentage = result.getWinPercentage();
          }
        }
        if (maxWinTerritory != null) {
          moveMap.get(maxWinTerritory).addTempUnit(unit);
          moveMap.get(maxWinTerritory).setBattleResult(null);
          it.remove();

          // If carrier has dependent allied fighters then move them too
          if (Matches.UnitIsCarrier.match(unit)) {
            final Territory unitTerritory = unitTerritoryMap.get(unit);
            final Map<Unit, Collection<Unit>> carrierMustMoveWith =
                MoveValidator.carrierMustMoveWith(unitTerritory.getUnits().getUnits(), unitTerritory, data, player);
            if (carrierMustMoveWith.containsKey(unit)) {
              moveMap.get(maxWinTerritory).getTempUnits().addAll(carrierMustMoveWith.get(unit));
            }
          }
        }
      }

      // Set air units in territories
      for (final Iterator<Unit> it = sortedUnitMoveOptions.keySet().iterator(); it.hasNext();) {
        final Unit unit = it.next();
        Territory maxWinTerritory = null;
        double maxWinPercentage = -1;
        for (final Territory t : sortedUnitMoveOptions.get(unit)) {
          if (t.isWater() && Matches.UnitIsAir.match(unit)) {
            if (!transportUtils.validateCarrierCapacity(player, t,
                moveMap.get(t).getAllDefendersForCarrierCalcs(data, player), unit)) {
              continue; // skip moving air to water if not enough carrier capacity
            }
          }
          if (!t.isWater() && !t.getOwner().equals(player) && Matches.UnitIsAir.match(unit)
              && !ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t)) {
            continue; // skip moving air units to allied land without a factory
          }
          List<Unit> defendingUnits =
              Match.getMatches(moveMap.get(t).getAllDefenders(), ProMatches.unitIsAlliedNotOwnedAir(player, data)
                  .invert());
          if (t.isWater()) {
            defendingUnits = moveMap.get(t).getAllDefenders();
          }
          if (moveMap.get(t).getBattleResult() == null) {
            moveMap.get(t).setBattleResult(
                battleUtils.estimateDefendBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits,
                    moveMap.get(t).getMaxEnemyBombardUnits()));
          }
          final ProBattleResultData result = moveMap.get(t).getBattleResult();
          final boolean hasFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t);
          if (result.getWinPercentage() > maxWinPercentage
              && ((t.equals(myCapital) && result.getWinPercentage() > (100 - WIN_PERCENTAGE))
                  || (hasFactory && result.getWinPercentage() > (100 - MIN_WIN_PERCENTAGE)) || result.getTUVSwing() >= 0)) {
            maxWinTerritory = t;
            maxWinPercentage = result.getWinPercentage();
          }
        }
        if (maxWinTerritory != null) {
          moveMap.get(maxWinTerritory).addTempUnit(unit);
          moveMap.get(maxWinTerritory).setBattleResult(null);
          it.remove();
        }
      }

      // Loop through all my transports and see which territories they can defend from current list
      final List<Unit> alreadyMovedTransports = new ArrayList<Unit>();
      if (!Properties.getTransportCasualtiesRestricted(data)) {
        final Map<Unit, Set<Territory>> transportDefendOptions = new HashMap<Unit, Set<Territory>>();
        for (final Unit unit : transportMoveMap.keySet()) {

          // Find number of defend options
          final Set<Territory> canDefendTerritories = new HashSet<Territory>();
          for (final ProAttackTerritoryData attackTerritoryData : territoriesToTryToDefend) {
            if (transportMoveMap.get(unit).contains(attackTerritoryData.getTerritory())) {
              canDefendTerritories.add(attackTerritoryData.getTerritory());
            }
          }
          if (!canDefendTerritories.isEmpty()) {
            transportDefendOptions.put(unit, canDefendTerritories);
          }
        }

        // Loop through transports with move options and determine if any naval defense needs it
        for (final Unit transport : transportDefendOptions.keySet()) {

          // Find current naval defense that needs transport if it isn't transporting units
          for (final Territory t : transportDefendOptions.get(transport)) {
            if (!TransportTracker.isTransporting(transport)) {
              final List<Unit> defendingUnits = moveMap.get(t).getAllDefenders();
              if (moveMap.get(t).getBattleResult() == null) {
                moveMap.get(t).setBattleResult(
                    battleUtils.estimateDefendBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(),
                        defendingUnits, moveMap.get(t).getMaxEnemyBombardUnits()));
              }
              final ProBattleResultData result = moveMap.get(t).getBattleResult();
              if (result.getTUVSwing() > 0) {
                moveMap.get(t).addTempUnit(transport);
                moveMap.get(t).setBattleResult(null);
                alreadyMovedTransports.add(transport);
                ProLogger.trace("Adding defend transport to: " + t.getName());
                break;
              }
            }
          }
        }
      }

      // Loop through all my transports and see which can make amphib move
      final Map<Unit, Set<Territory>> amphibMoveOptions = new HashMap<Unit, Set<Territory>>();
      for (final ProAmphibData proTransportData : transportMapList) {

        // If already used to defend then ignore
        if (alreadyMovedTransports.contains(proTransportData.getTransport())) {
          continue;
        }

        // Find number of amphib move options
        final Set<Territory> canAmphibMoveTerritories = new HashSet<Territory>();
        for (final ProAttackTerritoryData attackTerritoryData : territoriesToTryToDefend) {
          if (proTransportData.getTransportMap().containsKey(attackTerritoryData.getTerritory())) {
            canAmphibMoveTerritories.add(attackTerritoryData.getTerritory());
          }
        }
        if (!canAmphibMoveTerritories.isEmpty()) {
          amphibMoveOptions.put(proTransportData.getTransport(), canAmphibMoveTerritories);
        }
      }

      // Loop through transports with amphib move options and determine if any land defense needs it
      for (final Unit transport : amphibMoveOptions.keySet()) {

        // Find current land defense results for territories that unit can amphib move
        for (final Territory t : amphibMoveOptions.get(transport)) {
          final List<Unit> defendingUnits = moveMap.get(t).getAllDefenders();
          if (moveMap.get(t).getBattleResult() == null) {
            moveMap.get(t).setBattleResult(
                battleUtils.estimateDefendBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits,
                    moveMap.get(t).getMaxEnemyBombardUnits()));
          }
          final ProBattleResultData result = moveMap.get(t).getBattleResult();
          final boolean hasFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t);
          if ((hasFactory && (result.getWinPercentage() > (100 - WIN_PERCENTAGE))) || result.getTUVSwing() > 0) {

            // Get all units that have already moved
            final List<Unit> alreadyMovedUnits = new ArrayList<Unit>();
            for (final Territory t2 : moveMap.keySet()) {
              alreadyMovedUnits.addAll(moveMap.get(t2).getUnits());
              alreadyMovedUnits.addAll(moveMap.get(t2).getTempUnits());
            }

            // Find units that haven't moved and can be transported
            boolean addedAmphibUnits = false;
            for (final ProAmphibData proTransportData : transportMapList) {
              if (proTransportData.getTransport().equals(transport)) {

                // Find units to transport
                final Set<Territory> territoriesCanLoadFrom = proTransportData.getTransportMap().get(t);
                final List<Unit> amphibUnitsToAdd =
                    transportUtils.getUnitsToTransportFromTerritories(player, transport, territoriesCanLoadFrom,
                        alreadyMovedUnits);
                if (amphibUnitsToAdd.isEmpty()) {
                  continue;
                }

                // Find safest territory to unload from
                double minStrengthDifference = Double.POSITIVE_INFINITY;
                Territory minTerritory = null;
                final Set<Territory> territoriesToMoveTransport =
                    data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnits(player, data, false));
                final Set<Territory> loadFromTerritories = new HashSet<Territory>();
                for (final Unit u : amphibUnitsToAdd) {
                  loadFromTerritories.add(unitTerritoryMap.get(u));
                }
                for (final Territory territoryToMoveTransport : territoriesToMoveTransport) {
                  if (proTransportData.getSeaTransportMap().containsKey(territoryToMoveTransport)
                      && proTransportData.getSeaTransportMap().get(territoryToMoveTransport)
                          .containsAll(loadFromTerritories) && moveMap.get(territoryToMoveTransport) != null
                      && (moveMap.get(territoryToMoveTransport).isCanHold() || hasFactory)) {
                    final List<Unit> attackers = moveMap.get(territoryToMoveTransport).getMaxEnemyUnits();
                    final List<Unit> defenders = moveMap.get(territoryToMoveTransport).getAllDefenders();
                    defenders.add(transport);
                    final double strengthDifference =
                        battleUtils.estimateStrengthDifference(territoryToMoveTransport, attackers, defenders);
                    if (strengthDifference < minStrengthDifference) {
                      minTerritory = territoryToMoveTransport;
                      minStrengthDifference = strengthDifference;
                    }
                  }
                }
                if (minTerritory != null) {

                  // Add amphib defense
                  moveMap.get(t).getTransportTerritoryMap().put(transport, minTerritory);
                  moveMap.get(t).addTempUnits(amphibUnitsToAdd);
                  moveMap.get(t).putTempAmphibAttackMap(transport, amphibUnitsToAdd);
                  moveMap.get(t).setBattleResult(null);
                  for (final Unit unit : amphibUnitsToAdd) {
                    sortedUnitMoveOptions.remove(unit);
                  }
                  ProLogger.trace("Adding amphibious defense to: " + t + ", units=" + amphibUnitsToAdd
                      + ", unloadTerritory=" + minTerritory);
                  addedAmphibUnits = true;
                  break;
                }
              }
            }
            if (addedAmphibUnits) {
              break;
            }
          }
        }
      }

      // Determine if all defenses are successful
      boolean areSuccessful = true;
      boolean containsCapital = false;
      ProLogger.debug("Current number of territories: " + numToDefend);
      for (final ProAttackTerritoryData patd : territoriesToTryToDefend) {
        final Territory t = patd.getTerritory();

        // Find defense result and hold value based on used defenders TUV
        final List<Unit> defendingUnits = moveMap.get(t).getAllDefenders();
        moveMap.get(t).setBattleResult(
            battleUtils.calculateBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits, moveMap
                .get(t).getMaxEnemyBombardUnits(), false));
        final ProBattleResultData result = patd.getBattleResult();
        int isFactory = 0;
        if (ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t)) {
          isFactory = 1;
        }
        int isMyCapital = 0;
        if (t.equals(myCapital)) {
          isMyCapital = 1;
          containsCapital = true;
        }
        final double extraUnitValue = BattleCalculator.getTUV(moveMap.get(t).getTempUnits(), playerCostMap);
        final List<Unit> unsafeTransports = new ArrayList<Unit>();
        for (final Unit transport : moveMap.get(t).getTransportTerritoryMap().keySet()) {
          final Territory transportTerritory = moveMap.get(t).getTransportTerritoryMap().get(transport);
          if (!moveMap.get(transportTerritory).isCanHold()) {
            unsafeTransports.add(transport);
          }
        }
        final int unsafeTransportValue = BattleCalculator.getTUV(unsafeTransports, playerCostMap);
        final double holdValue =
            extraUnitValue / 8 * (1 + 0.5 * isFactory) * (1 + 2 * isMyCapital) - unsafeTransportValue;

        // Find strategic value
        boolean hasHigherStrategicValue = true;
        if (!t.isWater() && !t.equals(myCapital) && !ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t)) {
          double totalValue = 0.0;
          final List<Unit> nonAirDefenders = Match.getMatches(moveMap.get(t).getTempUnits(), Matches.UnitIsNotAir);
          for (final Unit u : nonAirDefenders) {
            totalValue += territoryValueMap.get(unitTerritoryMap.get(u));
          }
          final double averageValue = totalValue / nonAirDefenders.size();
          if (territoryValueMap.get(t) < averageValue) {
            hasHigherStrategicValue = false;
            ProLogger.trace(t + " has lower value then move from with value=" + territoryValueMap.get(t)
                + ", averageMoveFromValue=" + averageValue);
          }
        }

        // Check if its worth defending
        if ((result.getTUVSwing() - holdValue) > patd.getMinBattleResult().getTUVSwing()
            || (!hasHigherStrategicValue && (result.getTUVSwing() + extraUnitValue / 2) >= patd.getMinBattleResult()
                .getTUVSwing())) {
          areSuccessful = false;
        }
        ProLogger.debug(patd.getResultString() + ", holdValue=" + holdValue + ", minTUVSwing="
            + patd.getMinBattleResult().getTUVSwing() + ", hasHighStrategicValue=" + hasHigherStrategicValue
            + ", defenders=" + defendingUnits + ", attackers=" + moveMap.get(t).getMaxEnemyUnits());
      }

      final Territory currentTerritory = prioritizedTerritories.get(numToDefend - 1).getTerritory();
      if (myCapital != null) {

        // Check capital defense
        if (containsCapital && !currentTerritory.equals(myCapital)
            && moveMap.get(myCapital).getBattleResult().getWinPercentage() > (100 - WIN_PERCENTAGE)) {
          if (!Collections.disjoint(moveMap.get(currentTerritory).getAllDefenders(), moveMap.get(myCapital)
              .getMaxDefenders())) {
            areSuccessful = false;
            ProLogger.debug("Capital isn't safe after defense moves with winPercentage="
                + moveMap.get(myCapital).getBattleResult().getWinPercentage());
          }
        }

        // Check capital local superiority
        if (!currentTerritory.isWater() && enemyDistance >= 2 && enemyDistance <= 3) {
          final int distance =
              data.getMap().getDistance(myCapital, currentTerritory,
                  ProMatches.territoryCanMoveLandUnits(player, data, true));
          if (distance > 0 && (enemyDistance == distance || enemyDistance == (distance - 1))
              && !battleUtils.territoryHasLocalLandSuperiorityAfterMoves(myCapital, enemyDistance, player, moveMap)) {
            areSuccessful = false;
            ProLogger.debug("Capital doesn't have local land superiority after defense moves with enemyDistance="
                + enemyDistance);
          }
        }
      }

      // Determine whether to try more territories, remove a territory, or end
      if (areSuccessful) {
        numToDefend++;
        for (final ProAttackTerritoryData patd : territoriesToTryToDefend) {
          patd.setCanAttack(true);
        }

        // Can defend all territories in list so end
        if (numToDefend > prioritizedTerritories.size()) {
          break;
        }
      } else {

        // Remove territory last territory in prioritized list since we can't hold them all
        ProLogger.debug("Removing territory: " + currentTerritory);
        prioritizedTerritories.get(numToDefend - 1).setCanHold(false);
        prioritizedTerritories.remove(numToDefend - 1);
        if (numToDefend > prioritizedTerritories.size()) {
          numToDefend--;
        }
      }
    }

    // Add temp units to move lists
    for (final Territory t : moveMap.keySet()) {

      // Handle allied units such as fighters on carriers
      final List<Unit> alliedUnits =
          Match.getMatches(moveMap.get(t).getTempUnits(), Matches.unitIsOwnedBy(player).invert());
      for (final Unit alliedUnit : alliedUnits) {
        moveMap.get(t).addCantMoveUnit(alliedUnit);
        moveMap.get(t).getTempUnits().remove(alliedUnit);
      }
      moveMap.get(t).addUnits(moveMap.get(t).getTempUnits());
      moveMap.get(t).putAllAmphibAttackMap(moveMap.get(t).getTempAmphibAttackMap());
      for (final Unit u : moveMap.get(t).getTempUnits()) {
        if (Matches.UnitIsTransport.match(u)) {
          transportMoveMap.remove(u);
          for (final Iterator<ProAmphibData> it = transportMapList.iterator(); it.hasNext();) {
            if (it.next().getTransport().equals(u)) {
              it.remove();
            }
          }
        } else {
          unitMoveMap.remove(u);
        }
      }
      for (final Unit u : moveMap.get(t).getTempAmphibAttackMap().keySet()) {
        transportMoveMap.remove(u);
        for (final Iterator<ProAmphibData> it = transportMapList.iterator(); it.hasNext();) {
          if (it.next().getTransport().equals(u)) {
            it.remove();
          }
        }
      }
      moveMap.get(t).getTempUnits().clear();
      moveMap.get(t).getTempAmphibAttackMap().clear();
    }
    ProLogger.debug("Final number of territories: " + (numToDefend - 1));
  }

  private void moveUnitsToBestTerritories(final Map<Territory, ProAttackTerritoryData> moveMap,
      final Map<Unit, Set<Territory>> unitMoveMap, final List<ProAmphibData> transportMapList,
      final Map<Unit, Set<Territory>> transportMoveMap) {

    while (true) {
      ProLogger.info("Move units to best value territories");
      final Set<Territory> territoriesToDefend = new HashSet<Territory>();
      final Map<Unit, Set<Territory>> currentUnitMoveMap = new HashMap<Unit, Set<Territory>>(unitMoveMap);
      final Map<Unit, Set<Territory>> currentTransportMoveMap = new HashMap<Unit, Set<Territory>>(transportMoveMap);
      final List<ProAmphibData> currentTransportMapList = new ArrayList<ProAmphibData>(transportMapList);

      // Reset lists
      for (final Territory t : moveMap.keySet()) {
        moveMap.get(t).getTempUnits().clear();
        for (final Unit transport : moveMap.get(t).getTempAmphibAttackMap().keySet()) {
          moveMap.get(t).getTransportTerritoryMap().remove(transport);
        }
        moveMap.get(t).getTempAmphibAttackMap().clear();
        moveMap.get(t).setBattleResult(null);
      }
      ProLogger.debug("Move amphib units");

      // Transport amphib units to best territory
      for (final Iterator<ProAmphibData> it = currentTransportMapList.iterator(); it.hasNext();) {
        final ProAmphibData amphibData = it.next();
        final Unit transport = amphibData.getTransport();

        // Get all units that have already moved
        final List<Unit> alreadyMovedUnits = new ArrayList<Unit>();
        for (final Territory t : moveMap.keySet()) {
          alreadyMovedUnits.addAll(moveMap.get(t).getUnits());
          alreadyMovedUnits.addAll(moveMap.get(t).getTempUnits());
        }

        // Transport amphib units to best land territory
        Territory maxValueTerritory = null;
        List<Unit> maxAmphibUnitsToAdd = null;
        double maxValue = Double.MIN_VALUE;
        double maxSeaValue = 0;
        Territory maxUnloadFromTerritory = null;
        for (final Territory t : amphibData.getTransportMap().keySet()) {
          if (moveMap.get(t).getValue() >= maxValue) {

            // Find units to load
            final Set<Territory> territoriesCanLoadFrom = amphibData.getTransportMap().get(t);
            final List<Unit> amphibUnitsToAdd =
                transportUtils.getUnitsToTransportThatCantMoveToHigherValue(player, transport, territoriesCanLoadFrom,
                    alreadyMovedUnits, moveMap, currentUnitMoveMap, moveMap.get(t).getValue());
            if (amphibUnitsToAdd.isEmpty()) {
              continue;
            }

            // Find best territory to move transport
            final Set<Territory> loadFromTerritories = new HashSet<Territory>();
            for (final Unit u : amphibUnitsToAdd) {
              loadFromTerritories.add(unitTerritoryMap.get(u));
            }
            final Set<Territory> territoriesToMoveTransport =
                data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnits(player, data, false));
            for (final Territory territoryToMoveTransport : territoriesToMoveTransport) {
              if (amphibData.getSeaTransportMap().containsKey(territoryToMoveTransport)
                  && amphibData.getSeaTransportMap().get(territoryToMoveTransport).containsAll(loadFromTerritories)
                  && moveMap.get(territoryToMoveTransport) != null
                  && moveMap.get(territoryToMoveTransport).isCanHold()
                  && (moveMap.get(t).getValue() > maxValue || moveMap.get(territoryToMoveTransport).getValue() > maxSeaValue)) {
                maxValueTerritory = t;
                maxAmphibUnitsToAdd = amphibUnitsToAdd;
                maxValue = moveMap.get(t).getValue();
                maxSeaValue = moveMap.get(territoryToMoveTransport).getValue();
                maxUnloadFromTerritory = territoryToMoveTransport;
              }
            }
          }
        }
        if (maxValueTerritory != null) {
          ProLogger.trace(transport + " moved to " + maxUnloadFromTerritory + " and unloading to best land at "
              + maxValueTerritory + " with " + maxAmphibUnitsToAdd + ", value=" + maxValue);
          moveMap.get(maxValueTerritory).addTempUnits(maxAmphibUnitsToAdd);
          moveMap.get(maxValueTerritory).putTempAmphibAttackMap(transport, maxAmphibUnitsToAdd);
          moveMap.get(maxValueTerritory).getTransportTerritoryMap().put(transport, maxUnloadFromTerritory);
          currentTransportMoveMap.remove(transport);
          for (final Unit unit : maxAmphibUnitsToAdd) {
            currentUnitMoveMap.remove(unit);
          }
          territoriesToDefend.add(maxUnloadFromTerritory);
          it.remove();
          continue;
        }

        // Transport amphib units to best sea territory
        for (final Territory t : amphibData.getSeaTransportMap().keySet()) {
          if (moveMap.get(t) != null && moveMap.get(t).getValue() > maxValue) {

            // Find units to load
            final Set<Territory> territoriesCanLoadFrom = amphibData.getSeaTransportMap().get(t);
            territoriesCanLoadFrom.removeAll(data.getMap().getNeighbors(t)); // Don't transport adjacent units
            final List<Unit> amphibUnitsToAdd =
                transportUtils.getUnitsToTransportThatCantMoveToHigherValue(player, transport, territoriesCanLoadFrom,
                    alreadyMovedUnits, moveMap, currentUnitMoveMap, 0.1);
            if (!amphibUnitsToAdd.isEmpty()) {
              maxValueTerritory = t;
              maxAmphibUnitsToAdd = amphibUnitsToAdd;
              maxValue = moveMap.get(t).getValue();
            }
          }
        }
        if (maxValueTerritory != null) {
          final Set<Territory> possibleUnloadTerritories =
              data.getMap().getNeighbors(maxValueTerritory,
                  ProMatches.territoryCanMoveLandUnitsAndIsAllied(player, data));
          Territory unloadToTerritory = null;
          int maxNumSeaNeighbors = 0;
          for (final Territory t : possibleUnloadTerritories) {
            final int numSeaNeighbors = data.getMap().getNeighbors(t, Matches.TerritoryIsWater).size();
            final boolean isAdjacentToEnemy =
                ProMatches.territoryIsOrAdjacentToEnemyNotNeutralLand(player, data).match(t);
            if (moveMap.get(t) != null && (moveMap.get(t).isCanHold() || !isAdjacentToEnemy)
                && numSeaNeighbors > maxNumSeaNeighbors) {
              unloadToTerritory = t;
              maxNumSeaNeighbors = numSeaNeighbors;
            }
          }
          if (unloadToTerritory != null) {
            moveMap.get(unloadToTerritory).addTempUnits(maxAmphibUnitsToAdd);
            moveMap.get(unloadToTerritory).putTempAmphibAttackMap(transport, maxAmphibUnitsToAdd);
            moveMap.get(unloadToTerritory).getTransportTerritoryMap().put(transport, maxValueTerritory);
            ProLogger.trace(transport + " moved to best sea at " + maxValueTerritory + " and unloading to "
                + unloadToTerritory + " with " + maxAmphibUnitsToAdd + ", value=" + maxValue);
          } else {
            moveMap.get(maxValueTerritory).addTempUnits(maxAmphibUnitsToAdd);
            moveMap.get(maxValueTerritory).putTempAmphibAttackMap(transport, maxAmphibUnitsToAdd);
            moveMap.get(maxValueTerritory).getTransportTerritoryMap().put(transport, maxValueTerritory);
            ProLogger.trace(transport + " moved to best sea at " + maxValueTerritory + " with " + maxAmphibUnitsToAdd
                + ", value=" + maxValue);
          }
          currentTransportMoveMap.remove(transport);
          for (final Unit unit : maxAmphibUnitsToAdd) {
            currentUnitMoveMap.remove(unit);
          }
          territoriesToDefend.add(maxValueTerritory);
          it.remove();
        }
      }
      ProLogger.debug("Move empty transports to best loading territory");

      // Move remaining transports to best loading territory if safe
      // TODO: consider which territory is 'safest'
      for (final Iterator<Unit> it = currentTransportMoveMap.keySet().iterator(); it.hasNext();) {
        final Unit transport = it.next();
        final Territory currentTerritory = unitTerritoryMap.get(transport);
        final int moves = TripleAUnit.get(transport).getMovementLeft();
        if (TransportTracker.isTransporting(transport) || moves <= 0) {
          continue;
        }
        final List<ProAttackTerritoryData> priorizitedLoadTerritories = new ArrayList<ProAttackTerritoryData>();
        for (final Territory t : moveMap.keySet()) {

          // Check if land with adjacent sea that can be reached and that I'm not already adjacent to
          final boolean territoryHasTransportableUnits =
              Matches.territoryHasUnitsThatMatch(ProMatches.unitIsOwnedTransportableUnitAndCanBeLoaded(player, false))
                  .match(t);
          final int distance =
              data.getMap().getDistance_IgnoreEndForCondition(currentTerritory, t,
                  ProMatches.territoryCanMoveSeaUnits(player, data, true));
          final boolean hasSeaNeighbor = Matches.territoryHasNeighborMatching(data, Matches.TerritoryIsWater).match(t);
          final boolean hasFactory = ProMatches.territoryHasInfraFactoryAndIsOwnedLand(player).match(t);
          if (!t.isWater() && hasSeaNeighbor && distance > 0
              && !(distance == 1 && territoryHasTransportableUnits && !hasFactory)) {

            // TODO: add calculation of transports vs units
            final double territoryValue = moveMap.get(t).getValue();
            final int numUnitsToLoad =
                Match.getMatches(moveMap.get(t).getAllDefenders(), ProMatches.unitIsOwnedTransportableUnit(player))
                    .size();
            final boolean hasUnconqueredFactory =
                ProMatches.territoryHasInfraFactoryAndIsOwnedLand(player).match(t)
                    && !AbstractMoveDelegate.getBattleTracker(data).wasConquered(t);
            int factoryProduction = 0;
            if (hasUnconqueredFactory) {
              factoryProduction = TerritoryAttachment.getProduction(t);
            }
            int numTurnsAway = (distance - 1) / moves;
            if (distance <= moves) {
              numTurnsAway = 0;
            }
            final double value = territoryValue + 0.5 * numTurnsAway - 0.1 * numUnitsToLoad - 0.1 * factoryProduction;
            moveMap.get(t).setLoadValue(value);
            priorizitedLoadTerritories.add(moveMap.get(t));
          }
        }

        // Sort prioritized territories
        Collections.sort(priorizitedLoadTerritories, new Comparator<ProAttackTerritoryData>() {
          @Override
          public int compare(final ProAttackTerritoryData t1, final ProAttackTerritoryData t2) {
            final double value1 = t1.getLoadValue();
            final double value2 = t2.getLoadValue();
            return Double.compare(value1, value2);
          }
        });

        // Move towards best loading territory if route is safe
        for (final ProAttackTerritoryData patd : priorizitedLoadTerritories) {
          boolean movedTransport = false;
          final Set<Territory> cantHoldTerritories = new HashSet<Territory>();
          while (true) {
            final Match<Territory> match =
                new CompositeMatchAnd<Territory>(ProMatches.territoryCanMoveSeaUnitsThrough(player, data, false),
                    Matches.territoryIsInList(cantHoldTerritories).invert());
            final Route route = data.getMap().getRoute_IgnoreEnd(currentTerritory, patd.getTerritory(), match);
            if (route == null
                || MoveValidator.validateCanal(route, Collections.singletonList(transport), player, data) != null) {
              break;
            }
            final List<Territory> territories = route.getAllTerritories();
            territories.remove(territories.size() - 1);
            final Territory moveToTerritory = territories.get(Math.min(territories.size() - 1, moves));
            final ProAttackTerritoryData patd2 = moveMap.get(moveToTerritory);
            if (patd2 != null && patd2.isCanHold()) {
              ProLogger.trace(transport + " moved towards best loading territory " + patd.getTerritory()
                  + " and moved to " + moveToTerritory);
              patd2.addTempUnit(transport);
              territoriesToDefend.add(moveToTerritory);
              it.remove();
              movedTransport = true;
              break;
            }
            if (!cantHoldTerritories.add(moveToTerritory)) {
              break;
            }
          }
          if (movedTransport) {
            break;
          }
        }
      }
      ProLogger.debug("Move remaining transports to safest territory");

      // Move remaining transports to safest territory
      for (final Iterator<Unit> it = currentTransportMoveMap.keySet().iterator(); it.hasNext();) {
        final Unit transport = it.next();

        // Get all units that have already moved
        final List<Unit> alreadyMovedUnits = new ArrayList<Unit>();
        for (final Territory t : moveMap.keySet()) {
          alreadyMovedUnits.addAll(moveMap.get(t).getUnits());
        }

        // Find safest territory
        double minStrengthDifference = Double.POSITIVE_INFINITY;
        Territory minTerritory = null;
        for (final Territory t : currentTransportMoveMap.get(transport)) {
          final List<Unit> attackers = moveMap.get(t).getMaxEnemyUnits();
          final List<Unit> defenders = moveMap.get(t).getMaxDefenders();
          defenders.removeAll(alreadyMovedUnits);
          defenders.addAll(moveMap.get(t).getUnits());
          defenders.removeAll(transportUtils.getAirThatCantLandOnCarrier(player, t, defenders));
          final double strengthDifference = battleUtils.estimateStrengthDifference(t, attackers, defenders);

          // TODO: add logic to move towards closest factory
          ProLogger.trace(transport + " at " + t + ", strengthDifference=" + strengthDifference + ", attackers="
              + attackers + ", defenders=" + defenders);
          if (strengthDifference < minStrengthDifference) {
            minStrengthDifference = strengthDifference;
            minTerritory = t;
          }
        }
        if (minTerritory != null) {

          // If transporting units then unload to safe territory
          // TODO: consider which is 'safest'
          if (TransportTracker.isTransporting(transport)) {
            final List<Unit> amphibUnits = (List<Unit>) TransportTracker.transporting(transport);
            final Set<Territory> possibleUnloadTerritories =
                data.getMap().getNeighbors(minTerritory, ProMatches.territoryCanMoveLandUnitsAndIsAllied(player, data));
            if (!possibleUnloadTerritories.isEmpty()) {
              // Find best unload territory
              Territory unloadToTerritory = possibleUnloadTerritories.iterator().next();
              for (final Territory t : possibleUnloadTerritories) {
                if (moveMap.get(t) != null && moveMap.get(t).isCanHold()) {
                  unloadToTerritory = t;
                }
              }
              ProLogger.trace(transport + " moved to safest territory at " + minTerritory + " and unloading to "
                  + unloadToTerritory + " with " + amphibUnits + ", strengthDifference=" + minStrengthDifference);
              moveMap.get(unloadToTerritory).addTempUnits(amphibUnits);
              moveMap.get(unloadToTerritory).putTempAmphibAttackMap(transport, amphibUnits);
              moveMap.get(unloadToTerritory).getTransportTerritoryMap().put(transport, minTerritory);
              for (final Unit unit : amphibUnits) {
                currentUnitMoveMap.remove(unit);
              }
              it.remove();
            } else {

              // Move transport with units since no unload options
              ProLogger.trace(transport + " moved to safest territory at " + minTerritory + " with " + amphibUnits
                  + ", strengthDifference=" + minStrengthDifference);
              moveMap.get(minTerritory).addTempUnits(amphibUnits);
              moveMap.get(minTerritory).putTempAmphibAttackMap(transport, amphibUnits);
              moveMap.get(minTerritory).getTransportTerritoryMap().put(transport, minTerritory);
              for (final Unit unit : amphibUnits) {
                currentUnitMoveMap.remove(unit);
              }
              it.remove();
            }
          } else {

            // If not transporting units
            ProLogger.trace(transport + " moved to safest territory at " + minTerritory + ", strengthDifference="
                + minStrengthDifference);
            moveMap.get(minTerritory).addTempUnit(transport);
            it.remove();
          }
        }
      }

      // Get all transport final territories
      moveUtils.calculateAmphibRoutes(player, new ArrayList<Collection<Unit>>(), new ArrayList<Route>(),
          new ArrayList<Collection<Unit>>(), moveMap, false);
      for (final Territory t : moveMap.keySet()) {
        for (final Unit u : moveMap.get(t).getTransportTerritoryMap().keySet()) {
          if (moveMap.get(moveMap.get(t).getTransportTerritoryMap().get(u)) != null) {
            moveMap.get(moveMap.get(t).getTransportTerritoryMap().get(u)).addTempUnit(u);
          }
        }
      }
      ProLogger.debug("Move sea units");

      // Move sea units to defend transports
      for (final Iterator<Unit> it = currentUnitMoveMap.keySet().iterator(); it.hasNext();) {
        final Unit u = it.next();
        if (Matches.UnitIsSea.match(u)) {
          for (final Territory t : currentUnitMoveMap.get(u)) {
            if (moveMap.get(t).isCanHold() && !moveMap.get(t).getAllDefenders().isEmpty()
                && Match.someMatch(moveMap.get(t).getAllDefenders(), ProMatches.unitIsOwnedTransport(player))) {
              final List<Unit> defendingUnits =
                  Match.getMatches(moveMap.get(t).getAllDefenders(), Matches.UnitIsNotLand);
              if (moveMap.get(t).getBattleResult() == null) {
                moveMap.get(t).setBattleResult(
                    battleUtils.estimateDefendBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(),
                        defendingUnits, moveMap.get(t).getMaxEnemyBombardUnits()));
              }
              final ProBattleResultData result = moveMap.get(t).getBattleResult();
              ProLogger.trace(t.getName() + " TUVSwing=" + result.getTUVSwing() + ", Win%=" + result.getWinPercentage()
                  + ", enemyAttackers=" + moveMap.get(t).getMaxEnemyUnits().size() + ", defenders="
                  + defendingUnits.size());
              if (result.getWinPercentage() > (100 - WIN_PERCENTAGE) || result.getTUVSwing() > 0) {
                ProLogger.trace(u + " added sea to defend transport at " + t);
                moveMap.get(t).addTempUnit(u);
                moveMap.get(t).setBattleResult(null);
                territoriesToDefend.add(t);
                it.remove();

                // If carrier has dependent allied fighters then move them too
                if (Matches.UnitIsCarrier.match(u)) {
                  final Territory unitTerritory = unitTerritoryMap.get(u);
                  final Map<Unit, Collection<Unit>> carrierMustMoveWith =
                      MoveValidator.carrierMustMoveWith(unitTerritory.getUnits().getUnits(), unitTerritory, data,
                          player);
                  if (carrierMustMoveWith.containsKey(u)) {
                    moveMap.get(t).getTempUnits().addAll(carrierMustMoveWith.get(u));
                  }
                }
                break;
              }
            }
          }
        }
      }

      // Move air units to defend transports
      for (final Iterator<Unit> it = currentUnitMoveMap.keySet().iterator(); it.hasNext();) {
        final Unit u = it.next();
        if (Matches.UnitCanLandOnCarrier.match(u)) {
          for (final Territory t : currentUnitMoveMap.get(u)) {
            if (t.isWater() && moveMap.get(t).isCanHold() && !moveMap.get(t).getAllDefenders().isEmpty()
                && Match.someMatch(moveMap.get(t).getAllDefenders(), ProMatches.unitIsOwnedTransport(player))) {
              if (!transportUtils.validateCarrierCapacity(player, t,
                  moveMap.get(t).getAllDefendersForCarrierCalcs(data, player), u)) {
                continue;
              }
              final List<Unit> defendingUnits =
                  Match.getMatches(moveMap.get(t).getAllDefenders(), Matches.UnitIsNotLand);
              if (moveMap.get(t).getBattleResult() == null) {
                moveMap.get(t).setBattleResult(
                    battleUtils.estimateDefendBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(),
                        defendingUnits, moveMap.get(t).getMaxEnemyBombardUnits()));
              }
              final ProBattleResultData result = moveMap.get(t).getBattleResult();
              ProLogger.trace(t.getName() + " TUVSwing=" + result.getTUVSwing() + ", Win%=" + result.getWinPercentage()
                  + ", enemyAttackers=" + moveMap.get(t).getMaxEnemyUnits().size() + ", defenders="
                  + defendingUnits.size());
              if (result.getWinPercentage() > (100 - WIN_PERCENTAGE) || result.getTUVSwing() > 0) {
                ProLogger.trace(u + " added air to defend transport at " + t);
                moveMap.get(t).addTempUnit(u);
                moveMap.get(t).setBattleResult(null);
                territoriesToDefend.add(t);
                it.remove();
                break;
              }
            }
          }
        }
      }

      // Move sea units to best location or safest location
      for (final Iterator<Unit> it = currentUnitMoveMap.keySet().iterator(); it.hasNext();) {
        final Unit u = it.next();
        if (Matches.UnitIsSea.match(u)) {
          Territory maxValueTerritory = null;
          double maxValue = 0;
          for (final Territory t : currentUnitMoveMap.get(u)) {
            if (moveMap.get(t).isCanHold()) {
              final int transports =
                  Match.countMatches(moveMap.get(t).getAllDefenders(), ProMatches.unitIsOwnedTransport(player));
              final double value =
                  (1 + transports) * moveMap.get(t).getSeaValue() + (1 + transports * 100) * moveMap.get(t).getValue()
                      / 10000;
              ProLogger.trace(t + ", value=" + value + ", seaValue=" + moveMap.get(t).getSeaValue() + ", tValue="
                  + moveMap.get(t).getValue() + ", transports=" + transports);
              if (value > maxValue) {
                maxValue = value;
                maxValueTerritory = t;
              }
            }
          }
          if (maxValueTerritory != null) {
            ProLogger.trace(u + " added to best territory " + maxValueTerritory + ", value=" + maxValue);
            moveMap.get(maxValueTerritory).addTempUnit(u);
            moveMap.get(maxValueTerritory).setBattleResult(null);
            territoriesToDefend.add(maxValueTerritory);
            it.remove();

            // If carrier has dependent allied fighters then move them too
            if (Matches.UnitIsCarrier.match(u)) {
              final Territory unitTerritory = unitTerritoryMap.get(u);
              final Map<Unit, Collection<Unit>> carrierMustMoveWith =
                  MoveValidator.carrierMustMoveWith(unitTerritory.getUnits().getUnits(), unitTerritory, data, player);
              if (carrierMustMoveWith.containsKey(u)) {
                moveMap.get(maxValueTerritory).getTempUnits().addAll(carrierMustMoveWith.get(u));
              }
            }
          } else {

            // Get all units that have already moved
            final List<Unit> alreadyMovedUnits = new ArrayList<Unit>();
            for (final Territory t : moveMap.keySet()) {
              alreadyMovedUnits.addAll(moveMap.get(t).getUnits());
            }

            // Find safest territory
            double minStrengthDifference = Double.POSITIVE_INFINITY;
            Territory minTerritory = null;
            for (final Territory t : currentUnitMoveMap.get(u)) {
              final List<Unit> attackers = moveMap.get(t).getMaxEnemyUnits();
              final List<Unit> defenders = moveMap.get(t).getMaxDefenders();
              defenders.removeAll(alreadyMovedUnits);
              defenders.addAll(moveMap.get(t).getUnits());
              final double strengthDifference = battleUtils.estimateStrengthDifference(t, attackers, defenders);
              if (strengthDifference < minStrengthDifference) {
                minStrengthDifference = strengthDifference;
                minTerritory = t;
              }
            }
            if (minTerritory != null) {
              ProLogger.trace(u + " moved to safest territory at " + minTerritory + ", strengthDifference="
                  + minStrengthDifference);
              moveMap.get(minTerritory).addTempUnit(u);
              moveMap.get(minTerritory).setBattleResult(null);
              it.remove();

              // If carrier has dependent allied fighters then move them too
              if (Matches.UnitIsCarrier.match(u)) {
                final Territory unitTerritory = unitTerritoryMap.get(u);
                final Map<Unit, Collection<Unit>> carrierMustMoveWith =
                    MoveValidator.carrierMustMoveWith(unitTerritory.getUnits().getUnits(), unitTerritory, data, player);
                if (carrierMustMoveWith.containsKey(u)) {
                  moveMap.get(minTerritory).getTempUnits().addAll(carrierMustMoveWith.get(u));
                }
              }
            } else {
              final Territory currentTerritory = unitTerritoryMap.get(u);
              ProLogger.trace(u + " added to current territory since no better options at " + currentTerritory);
              moveMap.get(currentTerritory).addTempUnit(u);
              moveMap.get(currentTerritory).setBattleResult(null);
              it.remove();
            }
          }
        }
      }

      // Determine if all defenses are successful
      ProLogger.debug("Checking if all sea moves are safe for " + territoriesToDefend);
      boolean areSuccessful = true;
      for (final Territory t : territoriesToDefend) {

        // Find result with temp units
        final List<Unit> defendingUnits = moveMap.get(t).getAllDefenders();
        moveMap.get(t).setBattleResult(
            battleUtils.calculateBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits, moveMap
                .get(t).getMaxEnemyBombardUnits(), false));
        final ProBattleResultData result = moveMap.get(t).getBattleResult();
        int isWater = 0;
        if (t.isWater()) {
          isWater = 1;
        }
        final double extraUnitValue = BattleCalculator.getTUV(moveMap.get(t).getTempUnits(), playerCostMap);
        final double holdValue = result.getTUVSwing() - (extraUnitValue / 8 * (1 + isWater));

        // Find min result without temp units
        final List<Unit> minDefendingUnits = new ArrayList<Unit>(defendingUnits);
        minDefendingUnits.removeAll(moveMap.get(t).getTempUnits());
        final ProBattleResultData minResult =
            battleUtils.calculateBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), minDefendingUnits, moveMap
                .get(t).getMaxEnemyBombardUnits(), false);

        // Check if territory is worth defending with temp units
        if (holdValue > minResult.getTUVSwing()) {
          areSuccessful = false;
          moveMap.get(t).setCanHold(false);
          moveMap.get(t).setValue(0);
          moveMap.get(t).setSeaValue(0);
          ProLogger.trace(t + " unable to defend so removing with holdValue=" + holdValue + ", minTUVSwing="
              + minResult.getTUVSwing() + ", defenders=" + defendingUnits + ", enemyAttackers="
              + moveMap.get(t).getMaxEnemyUnits());
        }
        ProLogger.trace(moveMap.get(t).getResultString() + ", holdValue=" + holdValue + ", minTUVSwing="
            + minResult.getTUVSwing());
      }

      // Determine whether to try more territories, remove a territory, or end
      if (areSuccessful) {
        break;
      }
    }

    // Add temp units to move lists
    for (final Territory t : moveMap.keySet()) {

      // Handle allied units such as fighters on carriers
      final List<Unit> alliedUnits =
          Match.getMatches(moveMap.get(t).getTempUnits(), Matches.unitIsOwnedBy(player).invert());
      for (final Unit alliedUnit : alliedUnits) {
        moveMap.get(t).addCantMoveUnit(alliedUnit);
        moveMap.get(t).getTempUnits().remove(alliedUnit);
      }
      moveMap.get(t).addUnits(moveMap.get(t).getTempUnits());
      moveMap.get(t).putAllAmphibAttackMap(moveMap.get(t).getTempAmphibAttackMap());
      for (final Unit u : moveMap.get(t).getTempUnits()) {
        if (Matches.UnitIsTransport.match(u)) {
          transportMoveMap.remove(u);
          for (final Iterator<ProAmphibData> it = transportMapList.iterator(); it.hasNext();) {
            if (it.next().getTransport().equals(u)) {
              it.remove();
            }
          }
        } else {
          unitMoveMap.remove(u);
        }
      }
      for (final Unit u : moveMap.get(t).getTempAmphibAttackMap().keySet()) {
        transportMoveMap.remove(u);
        for (final Iterator<ProAmphibData> it = transportMapList.iterator(); it.hasNext();) {
          if (it.next().getTransport().equals(u)) {
            it.remove();
          }
        }
      }
      moveMap.get(t).getTempUnits().clear();
      moveMap.get(t).getTempAmphibAttackMap().clear();
    }
    ProLogger.info("Move land units");

    // Move land units to territory with highest value and highest transport capacity
    // TODO: consider if territory ends up being safe
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (Matches.UnitIsLand.match(u)) {
        Territory maxValueTerritory = null;
        double maxValue = 0;
        int maxNeedAmphibUnitValue = Integer.MIN_VALUE;
        for (final Territory t : unitMoveMap.get(u)) {
          if (moveMap.get(t).isCanHold() && moveMap.get(t).getValue() >= maxValue) {

            // Find transport capacity of neighboring (distance 1) transports
            final List<Unit> transports1 = new ArrayList<Unit>();
            final Set<Territory> seaNeighbors =
                data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnits(player, data, true));
            for (final Territory neighborTerritory : seaNeighbors) {
              if (moveMap.containsKey(neighborTerritory)) {
                transports1.addAll(Match.getMatches(moveMap.get(neighborTerritory).getAllDefenders(),
                    ProMatches.unitIsOwnedTransport(player)));
              }
            }
            int transportCapacity1 = 0;
            for (final Unit transport : transports1) {
              transportCapacity1 += UnitAttachment.get(transport.getType()).getTransportCapacity();
            }

            // Find transport capacity of nearby (distance 2) transports
            final List<Unit> transports2 = new ArrayList<Unit>();
            final Set<Territory> nearbySeaTerritories =
                data.getMap().getNeighbors(t, 2, ProMatches.territoryCanMoveSeaUnits(player, data, true));
            nearbySeaTerritories.removeAll(seaNeighbors);
            for (final Territory neighborTerritory : nearbySeaTerritories) {
              if (moveMap.containsKey(neighborTerritory)) {
                transports2.addAll(Match.getMatches(moveMap.get(neighborTerritory).getAllDefenders(),
                    ProMatches.unitIsOwnedTransport(player)));
              }
            }
            int transportCapacity2 = 0;
            for (final Unit transport : transports2) {
              transportCapacity2 += UnitAttachment.get(transport.getType()).getTransportCapacity();
            }
            final List<Unit> unitsToTransport =
                Match.getMatches(moveMap.get(t).getAllDefenders(), ProMatches.unitIsOwnedTransportableUnit(player));

            // Find transport cost of potential amphib units
            int transportCost = 0;
            for (final Unit unit : unitsToTransport) {
              transportCost += UnitAttachment.get(unit.getType()).getTransportCost();
            }

            // Find territory that needs amphib units that most
            int hasFactory = 0;
            if (ProMatches.territoryHasInfraFactoryAndIsOwnedLandAdjacentToSea(player, data).match(t)) {
              hasFactory = 1;
            }
            final int neededNeighborTransportValue = Math.max(0, transportCapacity1 - transportCost);
            final int neededNearbyTransportValue = Math.max(0, transportCapacity1 + transportCapacity2 - transportCost);
            final int needAmphibUnitValue =
                1000 * neededNeighborTransportValue + 100 * neededNearbyTransportValue + (1 + 10 * hasFactory)
                    * data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnits(player, data, true)).size();
            if (moveMap.get(t).getValue() > maxValue || needAmphibUnitValue > maxNeedAmphibUnitValue) {
              maxValue = moveMap.get(t).getValue();
              maxNeedAmphibUnitValue = needAmphibUnitValue;
              maxValueTerritory = t;
            }
          }
        }
        if (maxValueTerritory != null) {
          ProLogger.trace(u + " moved to " + maxValueTerritory + " with value=" + maxValue
              + ", numNeededTransportUnits=" + maxNeedAmphibUnitValue);
          moveMap.get(maxValueTerritory).addUnit(u);
          it.remove();
        }
      }
    }

    // Move land units towards nearest factory that is adjacent to the sea
    final Set<Territory> myFactoriesAdjacentToSea =
        new HashSet<Territory>(Match.getMatches(allTerritories,
            ProMatches.territoryHasInfraFactoryAndIsOwnedLandAdjacentToSea(player, data)));
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (Matches.UnitIsLand.match(u)) {
        int minDistance = Integer.MAX_VALUE;
        Territory minTerritory = null;
        for (final Territory t : unitMoveMap.get(u)) {
          if (moveMap.get(t).isCanHold()) {
            for (final Territory factory : myFactoriesAdjacentToSea) {
              int distance =
                  data.getMap().getDistance(t, factory, ProMatches.territoryCanMoveLandUnits(player, data, true));
              if (distance < 0) {
                distance = 10 * data.getMap().getDistance(t, factory);
              }
              if (distance >= 0 && distance < minDistance) {
                minDistance = distance;
                minTerritory = t;
              }
            }
          }
        }
        if (minTerritory != null) {
          ProLogger.trace(u.getType().getName() + " moved towards closest factory adjacent to sea at "
              + minTerritory.getName());
          moveMap.get(minTerritory).addUnit(u);
          it.remove();
        }
      }
    }
    ProLogger.info("Move land units to safest territory");

    // Move any remaining land units to safest territory (this is rarely used)
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (Matches.UnitIsLand.match(u)) {

        // Get all units that have already moved
        final List<Unit> alreadyMovedUnits = new ArrayList<Unit>();
        for (final Territory t : moveMap.keySet()) {
          alreadyMovedUnits.addAll(moveMap.get(t).getUnits());
        }

        // Find safest territory
        double minStrengthDifference = Double.POSITIVE_INFINITY;
        Territory minTerritory = null;
        for (final Territory t : unitMoveMap.get(u)) {
          final List<Unit> attackers = moveMap.get(t).getMaxEnemyUnits();
          final List<Unit> defenders = moveMap.get(t).getMaxDefenders();
          defenders.removeAll(alreadyMovedUnits);
          defenders.addAll(moveMap.get(t).getUnits());
          final double strengthDifference = battleUtils.estimateStrengthDifference(t, attackers, defenders);
          if (strengthDifference < minStrengthDifference) {
            minStrengthDifference = strengthDifference;
            minTerritory = t;
          }
        }
        if (minTerritory != null) {
          ProLogger.debug(u.getType().getName() + " moved to safest territory at " + minTerritory.getName()
              + " with strengthDifference=" + minStrengthDifference);
          moveMap.get(minTerritory).addUnit(u);
          it.remove();
        }
      }
    }
    ProLogger.info("Move air units");

    // Get list of territories that can't be held
    final List<Territory> territoriesThatCantBeHeld = new ArrayList<Territory>();
    for (final Territory t : moveMap.keySet()) {
      if (!moveMap.get(t).isCanHold()) {
        territoriesThatCantBeHeld.add(t);
      }
    }

    // Move air units to safe territory with most attack options
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (Matches.UnitIsNotAir.match(u)) {
        continue;
      }
      double maxAirValue = 0;
      Territory maxTerritory = null;
      for (final Territory t : unitMoveMap.get(u)) {
        if (!moveMap.get(t).isCanHold()) {
          continue;
        }
        if (t.isWater()
            && !transportUtils.validateCarrierCapacity(player, t,
                moveMap.get(t).getAllDefendersForCarrierCalcs(data, player), u)) {
          ProLogger.trace(t + " already at MAX carrier capacity");
          continue;
        }

        // Check to see if the territory is safe
        final List<Unit> defendingUnits = moveMap.get(t).getAllDefenders();
        defendingUnits.add(u);
        if (moveMap.get(t).getBattleResult() == null) {
          moveMap.get(t).setBattleResult(
              battleUtils.calculateBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), defendingUnits, moveMap
                  .get(t).getMaxEnemyBombardUnits(), false));
        }
        final ProBattleResultData result = moveMap.get(t).getBattleResult();
        ProLogger.trace(t + ", TUVSwing=" + result.getTUVSwing() + ", win%=" + result.getWinPercentage()
            + ", defendingUnits=" + defendingUnits + ", enemyAttackers=" + moveMap.get(t).getMaxEnemyUnits());
        if (result.getWinPercentage() >= MIN_WIN_PERCENTAGE || result.getTUVSwing() > 0) {
          moveMap.get(t).setCanHold(false);
          continue;
        }

        // Determine if territory can be held with owned units
        final List<Unit> myDefenders = Match.getMatches(defendingUnits, Matches.unitIsOwnedBy(player));
        final ProBattleResultData result2 =
            battleUtils.calculateBattleResults(player, t, moveMap.get(t).getMaxEnemyUnits(), myDefenders, moveMap
                .get(t).getMaxEnemyBombardUnits(), false);
        int cantHoldWithoutAllies = 0;
        if (result2.getWinPercentage() >= MIN_WIN_PERCENTAGE || result2.getTUVSwing() > 0) {
          cantHoldWithoutAllies = 1;
        }

        // Find number of potential attack options next turn
        final int range = TripleAUnit.get(u).getMaxMovementAllowed();
        final Set<Territory> possibleAttackTerritories =
            data.getMap().getNeighbors(t, range / 2, ProMatches.territoryCanMoveAirUnits(player, data, true));
        final int numEnemyAttackTerritories =
            Match.countMatches(possibleAttackTerritories, ProMatches.territoryIsEnemyNotNeutralLand(player, data));
        final int numLandAttackTerritories =
            Match.countMatches(possibleAttackTerritories, ProMatches
                .territoryIsEnemyOrCantBeHeldAndIsAdjacentToMyLandUnits(player, data, territoriesThatCantBeHeld));
        final int numSeaAttackTerritories =
            Match.countMatches(possibleAttackTerritories, Matches.territoryHasEnemySeaUnits(player, data));
        final Set<Territory> possibleMoveTerritories =
            data.getMap().getNeighbors(t, range, ProMatches.territoryCanMoveAirUnits(player, data, true));
        final int numNearbyEnemyTerritories =
            Match.countMatches(possibleMoveTerritories, ProMatches.territoryIsEnemyNotNeutralLand(player, data));

        // Check if number of attack territories and value are max
        final int isntFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t) ? 0 : 1;
        final int hasOwnedCarrier =
            Match.someMatch(moveMap.get(t).getAllDefenders(), ProMatches.UnitIsOwnedCarrier(player)) ? 1 : 0;
        final double airValue =
            (200.0 * numSeaAttackTerritories + 100 * numLandAttackTerritories + 10 * numEnemyAttackTerritories + numNearbyEnemyTerritories)
                / (1 + cantHoldWithoutAllies) / (1 + cantHoldWithoutAllies * isntFactory) * (1 + hasOwnedCarrier);
        if (airValue > maxAirValue) {
          maxAirValue = airValue;
          maxTerritory = t;
        }
        ProLogger.trace("Safe territory: " + t + ", airValue=" + airValue + ", numLandAttackOptions="
            + numLandAttackTerritories + ", numSeaAttackTerritories=" + numSeaAttackTerritories
            + ", numEnemyAttackTerritories=" + numEnemyAttackTerritories);
      }
      if (maxTerritory != null) {
        ProLogger.debug(u.getType().getName() + " added to safe territory with most attack options " + maxTerritory
            + ", maxAirValue=" + maxAirValue);
        moveMap.get(maxTerritory).addUnit(u);
        moveMap.get(maxTerritory).setBattleResult(null);
        it.remove();
      }
    }

    // Move air units to safest territory
    for (final Iterator<Unit> it = unitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      if (Matches.UnitIsNotAir.match(u)) {
        continue;
      }
      double minStrengthDifference = Double.POSITIVE_INFINITY;
      Territory minTerritory = null;
      for (final Territory t : unitMoveMap.get(u)) {
        if (t.isWater()
            && !transportUtils.validateCarrierCapacity(player, t,
                moveMap.get(t).getAllDefendersForCarrierCalcs(data, player), u)) {
          ProLogger.trace(t + " already at MAX carrier capacity");
          continue;
        }
        final List<Unit> attackers = moveMap.get(t).getMaxEnemyUnits();
        final List<Unit> defenders = moveMap.get(t).getAllDefenders();
        defenders.add(u);
        final double strengthDifference = battleUtils.estimateStrengthDifference(t, attackers, defenders);
        ProLogger.trace("Unsafe territory: " + t + " with strengthDifference=" + strengthDifference);
        if (strengthDifference < minStrengthDifference) {
          minStrengthDifference = strengthDifference;
          minTerritory = t;
        }
      }
      if (minTerritory != null) {
        ProLogger.debug(u.getType().getName() + " added to safest territory at " + minTerritory
            + " with strengthDifference=" + minStrengthDifference);
        moveMap.get(minTerritory).addUnit(u);
        it.remove();
      }
    }
  }

  private Map<Territory, ProAttackTerritoryData> moveInfraUnits(Map<Territory, ProAttackTerritoryData> factoryMoveMap,
      final Map<Territory, ProAttackTerritoryData> moveMap, final Map<Unit, Set<Territory>> infraUnitMoveMap) {
    ProLogger.info("Determine where to move infra units");

    // Move factory units
    if (factoryMoveMap == null) {
      ProLogger.debug("Creating factory move map");

      // Determine and store where to move factories
      factoryMoveMap = new HashMap<Territory, ProAttackTerritoryData>();
      for (final Iterator<Unit> it = infraUnitMoveMap.keySet().iterator(); it.hasNext();) {
        final Unit u = it.next();

        // Only check factory units
        if (Matches.UnitCanProduceUnits.match(u)) {
          Territory maxValueTerritory = null;
          double maxValue = 0;
          for (final Territory t : infraUnitMoveMap.get(u)) {
            if (!moveMap.get(t).isCanHold()) {
              continue;
            }

            // Find value by checking if territory is not conquered and doesn't already have a factory
            final List<Unit> units = new ArrayList<Unit>(moveMap.get(t).getCantMoveUnits());
            units.addAll(moveMap.get(t).getUnits());
            final int production = TerritoryAttachment.get(t).getProduction();
            double value = 0.1 * moveMap.get(t).getValue();
            if (ProMatches.territoryIsNotConqueredOwnedLand(player, data).match(t)
                && Match.noneMatch(units, Matches.UnitCanProduceUnitsAndIsInfrastructure)) {
              value = moveMap.get(t).getValue() * production + 0.01 * production;
            }
            ProLogger.trace(t.getName() + " has value=" + value + ", strategicValue=" + moveMap.get(t).getValue()
                + ", production=" + production);
            if (value > maxValue) {
              maxValue = value;
              maxValueTerritory = t;
            }
          }
          if (maxValueTerritory != null) {
            ProLogger.debug(u.getType().getName() + " moved to " + maxValueTerritory.getName() + " with value="
                + maxValue);
            moveMap.get(maxValueTerritory).addUnit(u);
            if (factoryMoveMap.containsKey(maxValueTerritory)) {
              factoryMoveMap.get(maxValueTerritory).addUnit(u);
            } else {
              final ProAttackTerritoryData patd = new ProAttackTerritoryData(maxValueTerritory);
              patd.addUnit(u);
              factoryMoveMap.put(maxValueTerritory, patd);
            }
            it.remove();
          }
        }
      }
    } else {
      ProLogger.debug("Using stored factory move map");

      // Transfer stored factory moves to move map
      for (final Territory t : factoryMoveMap.keySet()) {
        moveMap.get(t).addUnits(factoryMoveMap.get(t).getUnits());
      }
    }
    ProLogger.debug("Move infra AA units");

    // Move AA units
    for (final Iterator<Unit> it = infraUnitMoveMap.keySet().iterator(); it.hasNext();) {
      final Unit u = it.next();
      final Territory currentTerritory = unitTerritoryMap.get(u);

      // Only check AA units whose territory can't be held and don't have factories
      if (Matches.UnitIsAAforAnything.match(u) && !moveMap.get(currentTerritory).isCanHold()
          && !ProMatches.territoryHasInfraFactoryAndIsLand(player).match(currentTerritory)) {
        Territory maxValueTerritory = null;
        double maxValue = 0;
        for (final Territory t : infraUnitMoveMap.get(u)) {
          if (!moveMap.get(t).isCanHold()) {
            continue;
          }

          // Consider max stack of 1 AA in classic
          final Route r =
              data.getMap().getRoute_IgnoreEnd(
                  currentTerritory,
                  t,
                  ProMatches.territoryCanMoveLandUnitsThrough(player, data, u, currentTerritory, false,
                      new ArrayList<Territory>()));
          final MoveValidationResult mvr =
              MoveValidator.validateMove(Collections.singletonList(u), r, player, new ArrayList<Unit>(),
                  new HashMap<Unit, Collection<Unit>>(), true, null, data);
          if (!mvr.isMoveValid()) {
            continue;
          }

          // Find value and try to move to territory that doesn't already have AA
          final List<Unit> units = new ArrayList<Unit>(moveMap.get(t).getCantMoveUnits());
          units.addAll(moveMap.get(t).getUnits());
          final boolean hasAA = Match.someMatch(units, Matches.UnitIsAAforAnything);
          double value = moveMap.get(t).getValue();
          if (hasAA) {
            value *= 0.01;
          }
          ProLogger.trace(t.getName() + " has value=" + value);
          if (value > maxValue) {
            maxValue = value;
            maxValueTerritory = t;
          }
        }
        if (maxValueTerritory != null) {
          ProLogger.debug(u.getType().getName() + " moved to " + maxValueTerritory.getName() + " with value="
              + maxValue);
          moveMap.get(maxValueTerritory).addUnit(u);
          it.remove();
        }
      }
    }
    return factoryMoveMap;
  }

  private void logAttackMoves(final Map<Territory, ProAttackTerritoryData> moveMap,
      final Map<Unit, Set<Territory>> unitAttackMap, final List<ProAmphibData> transportMapList,
      final List<ProAttackTerritoryData> prioritizedTerritories,
      final Map<Territory, ProAttackTerritoryData> enemyAttackMap) {

    // Print prioritization
    ProLogger.debug("Prioritized territories:");
    for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories) {
      ProLogger.trace("  " + attackTerritoryData.getValue() + "  " + attackTerritoryData.getTerritory().getName());
    }

    // Print enemy territories with enemy units vs my units
    ProLogger.debug("Territories that can be attacked:");
    int count = 0;
    for (final Territory t : moveMap.keySet()) {
      count++;
      ProLogger.trace(count + ". ---" + t.getName());
      final Set<Unit> combinedUnits = new HashSet<Unit>(moveMap.get(t).getMaxUnits());
      combinedUnits.addAll(moveMap.get(t).getMaxAmphibUnits());
      combinedUnits.addAll(moveMap.get(t).getCantMoveUnits());
      ProLogger.trace("  --- My max units ---");
      final Map<String, Integer> printMap = new HashMap<String, Integer>();
      for (final Unit unit : combinedUnits) {
        if (printMap.containsKey(unit.toStringNoOwner())) {
          printMap.put(unit.toStringNoOwner(), printMap.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap.keySet()) {
        ProLogger.trace("    " + printMap.get(key) + " " + key);
      }
      ProLogger.trace("  --- My max amphib units ---");
      final Map<String, Integer> printMap5 = new HashMap<String, Integer>();
      for (final Unit unit : moveMap.get(t).getMaxAmphibUnits()) {
        if (printMap5.containsKey(unit.toStringNoOwner())) {
          printMap5.put(unit.toStringNoOwner(), printMap5.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap5.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap5.keySet()) {
        ProLogger.trace("    " + printMap5.get(key) + " " + key);
      }
      final List<Unit> units3 = moveMap.get(t).getUnits();
      ProLogger.trace("  --- My actual units ---");
      final Map<String, Integer> printMap3 = new HashMap<String, Integer>();
      for (final Unit unit : units3) {
        if (printMap3.containsKey(unit.toStringNoOwner())) {
          printMap3.put(unit.toStringNoOwner(), printMap3.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap3.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap3.keySet()) {
        ProLogger.trace("    " + printMap3.get(key) + " " + key);
      }
      ProLogger.trace("  --- Enemy units ---");
      final Map<String, Integer> printMap2 = new HashMap<String, Integer>();
      final List<Unit> units2 = moveMap.get(t).getMaxEnemyUnits();
      for (final Unit unit : units2) {
        if (printMap2.containsKey(unit.toStringNoOwner())) {
          printMap2.put(unit.toStringNoOwner(), printMap2.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap2.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap2.keySet()) {
        ProLogger.trace("    " + printMap2.get(key) + " " + key);
      }
      ProLogger.trace("  --- Enemy bombard units ---");
      final Map<String, Integer> printMap4 = new HashMap<String, Integer>();
      final Set<Unit> units4 = moveMap.get(t).getMaxEnemyBombardUnits();
      for (final Unit unit : units4) {
        if (printMap4.containsKey(unit.toStringNoOwner())) {
          printMap4.put(unit.toStringNoOwner(), printMap4.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap4.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap4.keySet()) {
        ProLogger.trace("    " + printMap4.get(key) + " " + key);
      }
    }
  }

  private Map<Unit, Territory> createUnitTerritoryMap(final PlayerID player) {
    final List<Territory> allTerritories = data.getMap().getTerritories();
    final List<Territory> myUnitTerritories =
        Match.getMatches(allTerritories, Matches.territoryHasUnitsOwnedBy(player));
    final Map<Unit, Territory> unitTerritoryMap = new HashMap<Unit, Territory>();
    for (final Territory t : myUnitTerritories) {
      final List<Unit> myUnits = t.getUnits().getMatches(Matches.unitIsOwnedBy(player));
      for (final Unit u : myUnits) {
        unitTerritoryMap.put(u, t);
      }
    }
    return unitTerritoryMap;
  }
}
