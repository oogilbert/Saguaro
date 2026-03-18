package oog.mega.saguaro.movement;

public final class RamEvent {
    public final long time;
    public final double ourScoreDelta;
    public final double opponentScoreDelta;
    public final double ourEnergyLoss;
    public final double enemyEnergyLoss;
    public final double ourCreditedDamageOnEnemyDelta;
    public final double enemyCreditedDamageOnUsDelta;
    public final boolean ourKillBonusApplied;
    public final boolean enemyKillBonusApplied;

    public RamEvent(long time,
                    double ourScoreDelta,
                    double opponentScoreDelta,
                    double ourEnergyLoss,
                    double enemyEnergyLoss,
                    double ourCreditedDamageOnEnemyDelta,
                    double enemyCreditedDamageOnUsDelta,
                    boolean ourKillBonusApplied,
                    boolean enemyKillBonusApplied) {
        this.time = time;
        this.ourScoreDelta = ourScoreDelta;
        this.opponentScoreDelta = opponentScoreDelta;
        this.ourEnergyLoss = ourEnergyLoss;
        this.enemyEnergyLoss = enemyEnergyLoss;
        this.ourCreditedDamageOnEnemyDelta = ourCreditedDamageOnEnemyDelta;
        this.enemyCreditedDamageOnUsDelta = enemyCreditedDamageOnUsDelta;
        this.ourKillBonusApplied = ourKillBonusApplied;
        this.enemyKillBonusApplied = enemyKillBonusApplied;
    }
}
