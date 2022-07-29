package com.elmakers.mine.bukkit.utility.platform.v1_19_1.goal;

import com.elmakers.mine.bukkit.utility.platform.Platform;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

public abstract class MagicOwnerGoal extends Goal {
    protected final Mob mob;
    protected final com.elmakers.mine.bukkit.utility.platform.v1_19_1.goal.MagicTamed tamed;

    public MagicOwnerGoal(Platform platform, Mob mob) {
        this.tamed = new MagicTamed(platform, mob);
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        return tamed.canUse();
    }

    @Override
    public void stop() {
        tamed.stop();
    }
}
