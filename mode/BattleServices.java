package oog.mega.saguaro.mode;

import oog.mega.saguaro.gun.HitChanceAimer;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.movement.MovementEngine;
import oog.mega.saguaro.movement.MovementController;

public final class BattleServices {
    private final MovementEngine movement;
    private final HitChanceAimer gun;
    private final BattleDataStore dataStore;

    public BattleServices(Info info, BattleDataStore dataStore) {
        if (info == null || dataStore == null) {
            throw new IllegalArgumentException("Battle services require non-null info and data store");
        }
        this.movement = new MovementEngine(info);
        this.gun = new HitChanceAimer(info);
        this.dataStore = dataStore;
    }

    public MovementController movement() {
        return movement;
    }

    public GunController gun() {
        return gun;
    }

    public BattleDataStore dataStore() {
        return dataStore;
    }

}
