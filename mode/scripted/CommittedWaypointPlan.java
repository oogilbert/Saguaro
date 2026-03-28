package oog.mega.saguaro.mode.scripted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.BattlePlan;

public final class CommittedWaypointPlan {
    private final long startTime;
    private final double battlefieldWidth;
    private final double battlefieldHeight;
    private final List<ScriptedWaypoint> waypoints;

    public CommittedWaypointPlan(RobotSnapshot start,
                                 double battlefieldWidth,
                                 double battlefieldHeight,
                                 List<ScriptedWaypoint> waypoints) {
        this(requireStartTime(start), battlefieldWidth, battlefieldHeight, waypoints);
    }

    public CommittedWaypointPlan(long startTime,
                                 double battlefieldWidth,
                                 double battlefieldHeight,
                                 List<ScriptedWaypoint> waypoints) {
        if (!Double.isFinite(battlefieldWidth) || !Double.isFinite(battlefieldHeight)
                || battlefieldWidth <= 0.0 || battlefieldHeight <= 0.0) {
            throw new IllegalArgumentException("Committed waypoint plan requires finite battlefield dimensions");
        }
        if (waypoints == null) {
            throw new IllegalArgumentException("Committed waypoint plan requires a non-null waypoint list");
        }
        this.startTime = startTime;
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        this.waypoints = copyAndValidateWaypoints(waypoints);
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return startTime + totalTicks();
    }

    public List<ScriptedWaypoint> getWaypoints() {
        return waypoints;
    }

    public int getElapsedTicks(long currentTime) {
        if (currentTime <= startTime) {
            return 0;
        }
        long rawTicks = currentTime - startTime;
        return clampTickOffset(rawTicks);
    }

    public int getRemainingTicks(long currentTime) {
        return Math.max(0, totalTicks() - getElapsedTicks(currentTime));
    }

    public boolean isComplete(long currentTime) {
        return getElapsedTicks(currentTime) >= totalTicks();
    }

    public int activeWaypointIndex(long currentTime) {
        if (waypoints.isEmpty() || isComplete(currentTime)) {
            return -1;
        }
        int elapsedTicks = getElapsedTicks(currentTime);
        int consumedTicks = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            consumedTicks += waypoints.get(i).durationTicks;
            if (elapsedTicks < consumedTicks) {
                return i;
            }
        }
        return -1;
    }

    public ScriptedWaypoint activeWaypoint(long currentTime) {
        int index = activeWaypointIndex(currentTime);
        return index >= 0 ? waypoints.get(index) : null;
    }

    public long waypointTime(int index) {
        if (index < 0 || index >= waypoints.size()) {
            throw new IllegalArgumentException("Waypoint index out of range: " + index);
        }
        int elapsedTicks = 0;
        for (int i = 0; i <= index; i++) {
            elapsedTicks += waypoints.get(i).durationTicks;
        }
        return startTime + elapsedTicks;
    }

    public int activeWaypointTicksRemaining(long currentTime) {
        int activeIndex = activeWaypointIndex(currentTime);
        if (activeIndex < 0) {
            return 0;
        }
        int elapsedTicks = getElapsedTicks(currentTime);
        int segmentEndTick = 0;
        for (int i = 0; i <= activeIndex; i++) {
            segmentEndTick += waypoints.get(i).durationTicks;
        }
        return segmentEndTick - elapsedTicks;
    }

    public List<ScriptedWaypoint> remainingWaypoints(long currentTime) {
        int activeIndex = activeWaypointIndex(currentTime);
        if (activeIndex < 0) {
            return Collections.emptyList();
        }

        List<ScriptedWaypoint> remaining = new ArrayList<ScriptedWaypoint>();
        remaining.add(new ScriptedWaypoint(
                waypoints.get(activeIndex).x,
                waypoints.get(activeIndex).y,
                activeWaypointTicksRemaining(currentTime)));
        for (int i = activeIndex + 1; i < waypoints.size(); i++) {
            ScriptedWaypoint waypoint = waypoints.get(i);
            remaining.add(new ScriptedWaypoint(waypoint.x, waypoint.y, waypoint.durationTicks));
        }
        return Collections.unmodifiableList(remaining);
    }

    public PhysicsUtil.PositionState plannedStateAt(RobotSnapshot currentState, int futureTickOffset) {
        PhysicsUtil.Trajectory trajectory = simulateRemainingTrajectory(currentState);
        if (trajectory == null || futureTickOffset >= trajectory.length()) {
            return null;
        }
        return trajectory.stateAt(futureTickOffset);
    }

    public PhysicsUtil.Trajectory simulateRemainingTrajectory(RobotSnapshot currentState) {
        return simulateRemainingTrajectory(currentState, false);
    }

    public PhysicsUtil.Trajectory simulateRemainingTrajectory(RobotSnapshot currentState,
                                                              boolean activeWaypointEndpointPhase) {
        if (currentState == null) {
            throw new IllegalArgumentException("Committed waypoint plan requires a non-null current snapshot");
        }
        List<ScriptedWaypoint> remaining = remainingWaypoints(currentState.time);
        if (remaining.isEmpty()) {
            return new PhysicsUtil.Trajectory(new PhysicsUtil.PositionState[]{toPositionState(currentState)});
        }
        return buildTrajectory(
                toPositionState(currentState),
                currentState.time,
                remaining,
                activeWaypointEndpointPhase,
                battlefieldWidth,
                battlefieldHeight);
    }

    public BattlePlan createExecutionPlan(RobotSnapshot currentState) {
        return createExecutionPlan(currentState, 0.0, 0.0);
    }

    public BattlePlan createExecutionPlan(RobotSnapshot currentState,
                                          double gunTurnAngle,
                                          double firePower) {
        return createExecutionPlan(currentState, gunTurnAngle, firePower, false);
    }

    public BattlePlan createExecutionPlan(RobotSnapshot currentState,
                                          double gunTurnAngle,
                                          double firePower,
                                          boolean activeWaypointEndpointPhase) {
        if (currentState == null) {
            throw new IllegalArgumentException("Committed waypoint plan requires a non-null current snapshot");
        }
        ScriptedWaypoint waypoint = activeWaypoint(currentState.time);
        if (waypoint == null) {
            return new BattlePlan(0.0, 0.0, gunTurnAngle, firePower);
        }
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                currentState.x,
                currentState.y,
                currentState.heading,
                currentState.velocity,
                waypoint.x,
                waypoint.y,
                PhysicsUtil.EndpointBehavior.PARK_AND_WAIT);
        if (activeWaypointEndpointPhase) {
            instruction[0] = 0.0;
            instruction[1] = 0.0;
        }
        return new BattlePlan(instruction[0], instruction[1], gunTurnAngle, firePower);
    }

    private int totalTicks() {
        int totalTicks = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            totalTicks += waypoints.get(i).durationTicks;
        }
        return totalTicks;
    }

    private static List<ScriptedWaypoint> copyAndValidateWaypoints(List<ScriptedWaypoint> rawWaypoints) {
        List<ScriptedWaypoint> copy = new ArrayList<ScriptedWaypoint>(rawWaypoints.size());
        for (int i = 0; i < rawWaypoints.size(); i++) {
            ScriptedWaypoint waypoint = rawWaypoints.get(i);
            if (waypoint == null) {
                throw new IllegalArgumentException("Waypoint list contains a null entry at index " + i);
            }
            if (waypoint.durationTicks < 0) {
                throw new IllegalArgumentException(
                        "Waypoint duration must be non-negative: index=" + i
                                + ", durationTicks=" + waypoint.durationTicks);
            }
            copy.add(waypoint);
        }
        return Collections.unmodifiableList(copy);
    }

    private static PhysicsUtil.PositionState toPositionState(RobotSnapshot snapshot) {
        return new PhysicsUtil.PositionState(snapshot.x, snapshot.y, snapshot.heading, snapshot.velocity);
    }

    private static PhysicsUtil.Trajectory buildTrajectory(PhysicsUtil.PositionState startState,
                                                          long startTime,
                                                          List<ScriptedWaypoint> waypoints,
                                                          boolean initialEndpointPhase,
                                                          double battlefieldWidth,
                                                          double battlefieldHeight) {
        List<PhysicsUtil.PositionState> states = new ArrayList<PhysicsUtil.PositionState>();
        states.add(startState);

        PhysicsUtil.PositionState currentState = startState;
        long currentTime = startTime;
        for (int i = 0; i < waypoints.size(); i++) {
            ScriptedWaypoint waypoint = waypoints.get(i);
            PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                    currentState,
                    waypoint.x,
                    waypoint.y,
                    currentTime,
                    null,
                    currentTime + waypoint.durationTicks,
                    PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                    PhysicsUtil.SteeringMode.DIRECT,
                    battlefieldWidth,
                    battlefieldHeight,
                    i == 0 && initialEndpointPhase);
            appendSegmentStates(states, segment);
            int segmentTicks = segment.length() - 1;
            currentState = segment.stateAt(segmentTicks);
            currentTime += segmentTicks;
        }
        return new PhysicsUtil.Trajectory(states.toArray(new PhysicsUtil.PositionState[0]));
    }

    private static void appendSegmentStates(List<PhysicsUtil.PositionState> states,
                                            PhysicsUtil.Trajectory segment) {
        for (int i = 1; i < segment.length(); i++) {
            states.add(segment.states[i]);
        }
    }

    private int clampTickOffset(long tickOffset) {
        if (tickOffset <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, tickOffset);
    }

    private static long requireStartTime(RobotSnapshot start) {
        if (start == null) {
            throw new IllegalArgumentException("Committed waypoint plan requires a non-null start snapshot");
        }
        return start.time;
    }
}
