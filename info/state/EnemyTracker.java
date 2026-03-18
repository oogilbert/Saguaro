package oog.mega.saguaro.info.state;

import java.util.List;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.info.wave.Wave;
import robocode.RobotDeathEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;

public final class EnemyTracker {
    private Saguaro robot;
    private RoundOutcomeProfile roundOutcomeProfile;
    private BattleDataStore dataStore;
    private EnemyInfo enemy;
    private String enemyName;
    private boolean dataLoaded;

    public void init(Saguaro robot, RoundOutcomeProfile roundOutcomeProfile, BattleDataStore dataStore) {
        if (robot == null || roundOutcomeProfile == null || dataStore == null) {
            throw new IllegalArgumentException(
                    "EnemyTracker requires non-null robot, round outcome profile, and data store");
        }
        this.robot = robot;
        this.roundOutcomeProfile = roundOutcomeProfile;
        this.dataStore = dataStore;
        enemy = null;
        enemyName = null;
        dataLoaded = false;
    }

    public void setRoundOutcomeProfile(RoundOutcomeProfile roundOutcomeProfile) {
        if (roundOutcomeProfile == null) {
            throw new IllegalArgumentException("Round outcome profile must be non-null");
        }
        this.roundOutcomeProfile = roundOutcomeProfile;
    }

    public void activateRoundOutcomeProfile(RoundOutcomeProfile roundOutcomeProfile) {
        setRoundOutcomeProfile(roundOutcomeProfile);
        if (robot != null && enemyName != null) {
            dataStore.ensureOpponentDataLoaded(robot, enemyName);
        }
    }

    public void updateTick() {
        if (enemy != null && enemy.alive) {
            enemy.updateTick();
        }
    }

    public EnemyInfo.UpdateResult onScannedRobot(ScannedRobotEvent event,
                                                 ObservationProfile observationProfile,
                                                 int accelerationSign,
                                                 int ticksSinceVelocityReversal,
                                                 int ticksSinceDecel,
                                                 List<Wave> existingEnemyWaves) {
        if (observationProfile == null) {
            throw new IllegalArgumentException("Observation profile must be non-null");
        }
        noteEnemyName(event.getName());
        if (!dataLoaded && robot != null && enemyName != null) {
            dataStore.ensureOpponentDataLoaded(robot, enemyName);
            dataLoaded = true;
        }
        if (enemy == null) {
            enemy = new EnemyInfo(robot.getTime());
        }

        return enemy.update(
                robot,
                event,
                observationProfile,
                accelerationSign,
                ticksSinceVelocityReversal,
                ticksSinceDecel,
                existingEnemyWaves);
    }

    public Wave onRobotDeath(RobotDeathEvent event,
                             ObservationProfile observationProfile,
                             List<Wave> existingEnemyWaves) {
        if (matchesTrackedEnemy(event.getName()) && enemy != null) {
            Wave syntheticDeathWave = enemy.maybeCreateSyntheticDeathWave(
                    robot,
                    observationProfile,
                    existingEnemyWaves);
            enemy.alive = false;
            return syntheticDeathWave;
        }
        return null;
    }

    public void onBulletHit(String targetName, double bulletPower) {
        if (matchesTrackedEnemy(targetName) && enemy != null) {
            enemy.energy -= Rules.getBulletDamage(bulletPower);
        }
    }

    public void onHitByBullet(String shooterName, double bulletPower) {
        if (matchesTrackedEnemy(shooterName) && enemy != null) {
            enemy.energy += 3 * bulletPower;
        }
    }

    public void onHitRobot(boolean myFault, double enemyEnergyAfterCollision) {
        if (enemy == null) {
            return;
        }
        if (myFault && enemyEnergyAfterCollision <= 0.0) {
            enemy.alive = false;
        }
        enemy.energy = enemyEnergyAfterCollision;
    }

    public EnemyInfo getEnemy() {
        return enemy;
    }

    public String getEnemyName() {
        return enemyName;
    }

    private boolean matchesTrackedEnemy(String observedName) {
        return enemyName != null && enemyName.equals(observedName);
    }

    private static boolean isCanonicalEnemyName(String observedEnemyName) {
        return observedEnemyName != null
                && !observedEnemyName.isEmpty()
                && observedEnemyName.charAt(0) != '#';
    }

    private void noteEnemyName(String observedEnemyName) {
        if (observedEnemyName == null || observedEnemyName.isEmpty()) {
            throw new IllegalArgumentException("Observed enemy name must be non-empty");
        }
        if (!isCanonicalEnemyName(observedEnemyName)) {
            // Robocode can emit placeholder aliases like "#1" before a stable 1v1 name is known.
            // Ignore those so persistence and 1v1 validation only key off the canonical scanned name.
            return;
        }
        if (enemyName == null) {
            enemyName = observedEnemyName;
            return;
        }
        if (!enemyName.equals(observedEnemyName)) {
            throw new IllegalStateException(
                    "Saguaro is configured for 1v1 only but saw a second opponent: " + observedEnemyName);
        }
    }
}
