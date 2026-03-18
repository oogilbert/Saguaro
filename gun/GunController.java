package oog.mega.saguaro.gun;

public interface GunController {
    double getOptimalFiringAngle(double firePower);

    double getOptimalUnconstrainedFiringAngle(double firePower);

    ShotSolution selectOptimalShotFromPosition(double shooterX,
                                               double shooterY,
                                               double targetX,
                                               double targetY,
                                               double firePower,
                                               double gunHeadingAtDecision,
                                               int ticksUntilFire);

    ShotSolution selectOptimalUnconstrainedShotFromPosition(double shooterX,
                                                            double shooterY,
                                                            double targetX,
                                                            double targetY,
                                                            double firePower,
                                                            int ticksUntilFire);

    ShotSolution evaluateShotAtAngleFromPosition(double shooterX,
                                                 double shooterY,
                                                 double targetX,
                                                 double targetY,
                                                 double firePower,
                                                 double desiredFiringAngle,
                                                 double gunHeadingAtDecision,
                                                 int ticksUntilFire);
}
