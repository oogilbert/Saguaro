package oog.mega.saguaro.mode.scoremax;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.WaveStrategy;

final class RenderPathSelector {
    private static final double SAFE_SPOT_ANGLE_EPSILON = 1e-4;
    private static final double FIRST_TARGET_EPSILON = 1e-6;
    private static final int PREFIRE_BRANCH_COUNT = 4;

    private RenderPathSelector() {
    }

    static List<CandidatePath> collectFirstSegmentDisplayPaths(List<CandidatePath> branchPaths,
                                                               CandidatePath selectedPath) {
        List<CandidatePath> displayPaths = new ArrayList<>();
        if (selectedPath == null) {
            return displayPaths;
        }
        if (branchPaths == null || branchPaths.isEmpty()) {
            displayPaths.add(selectedPath);
            return displayPaths;
        }

        List<CandidatePath> prefirePaths = collectPrefireDisplayPaths(branchPaths);
        if (selectedPath.firstWave != null && selectedPath.firstWave.isVirtual
                && prefirePaths.size() == PREFIRE_BRANCH_COUNT) {
            displayPaths.addAll(prefirePaths);
        } else {
            addFirstByStrategy(displayPaths, selectedPath, branchPaths, WaveStrategy.MAX_CW);
            addFirstByStrategy(displayPaths, selectedPath, branchPaths, WaveStrategy.MAX_CCW);
            addFirstByStrategy(displayPaths, selectedPath, branchPaths, WaveStrategy.MOVE_OUT);
            addFirstByStrategy(displayPaths, selectedPath, branchPaths, WaveStrategy.MOVE_IN);
            addFirstByStrategy(displayPaths, selectedPath, branchPaths, WaveStrategy.STRAIGHT);
        }

        if (displayPaths.isEmpty()) {
            displayPaths.add(selectedPath);
        } else if (!displayPaths.contains(selectedPath)) {
            int replacementIndex = replacementIndexForSelected(displayPaths, selectedPath);
            if (replacementIndex >= 0) {
                displayPaths.set(replacementIndex, selectedPath);
            }
        }
        return displayPaths;
    }

    private static boolean hasSameSafeSpot(CandidatePath a, CandidatePath b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.firstWave == null || b.firstWave == null) {
            return false;
        }
        if (Double.isNaN(a.firstWaveTargetAngle) || Double.isNaN(b.firstWaveTargetAngle)) {
            return false;
        }
        if (a.firstWave.fireTime != b.firstWave.fireTime) {
            return false;
        }
        if (Double.doubleToLongBits(a.firstWave.originX) != Double.doubleToLongBits(b.firstWave.originX)
                || Double.doubleToLongBits(a.firstWave.originY) != Double.doubleToLongBits(b.firstWave.originY)) {
            return false;
        }
        double delta = Math.abs(MathUtils.normalizeAngle(a.firstWaveTargetAngle - b.firstWaveTargetAngle));
        return delta <= SAFE_SPOT_ANGLE_EPSILON;
    }

    private static boolean hasSameFirstTarget(CandidatePath a, CandidatePath b) {
        if (a == null || b == null) {
            return false;
        }
        if (Double.isNaN(a.firstTargetX) || Double.isNaN(a.firstTargetY)
                || Double.isNaN(b.firstTargetX) || Double.isNaN(b.firstTargetY)) {
            return false;
        }
        return Math.abs(a.firstTargetX - b.firstTargetX) <= FIRST_TARGET_EPSILON
                && Math.abs(a.firstTargetY - b.firstTargetY) <= FIRST_TARGET_EPSILON;
    }

    private static List<CandidatePath> collectPrefireDisplayPaths(List<CandidatePath> branchPaths) {
        List<CandidatePath> prefirePaths = new ArrayList<>();
        for (CandidatePath path : branchPaths) {
            if (path == null || path.firstWave == null || !path.firstWave.isVirtual) {
                continue;
            }
            boolean duplicate = false;
            for (CandidatePath existing : prefirePaths) {
                if (hasSameFirstTarget(existing, path)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                prefirePaths.add(path);
            }
        }
        return prefirePaths;
    }

    private static void addFirstByStrategy(List<CandidatePath> destination,
                                           CandidatePath selectedPath,
                                           List<CandidatePath> branchPaths,
                                           WaveStrategy strategy) {
        for (CandidatePath path : branchPaths) {
            if (path == selectedPath
                    && path.firstWaveStrategy == strategy
                    && hasSameSafeSpot(path, selectedPath)) {
                destination.add(path);
                return;
            }
        }
        for (CandidatePath path : branchPaths) {
            if (path == null || path.firstWaveStrategy != strategy || !hasSameSafeSpot(path, selectedPath)) {
                continue;
            }
            destination.add(path);
            return;
        }
    }

    private static int replacementIndexForSelected(List<CandidatePath> displayPaths, CandidatePath selectedPath) {
        for (int i = 0; i < displayPaths.size(); i++) {
            CandidatePath existing = displayPaths.get(i);
            if (existing == null) {
                continue;
            }
            if (existing.firstWaveStrategy == selectedPath.firstWaveStrategy
                    && hasSameSafeSpot(existing, selectedPath)) {
                return i;
            }
        }
        for (int i = 0; i < displayPaths.size(); i++) {
            CandidatePath existing = displayPaths.get(i);
            if (hasSameFirstTarget(existing, selectedPath)) {
                return i;
            }
        }
        return -1;
    }
}
