package com.elmakers.mine.bukkit.spell.builtin;

import com.elmakers.mine.bukkit.api.magic.Mage;
import com.elmakers.mine.bukkit.api.magic.MageController;
import com.elmakers.mine.bukkit.api.spell.Spell;
import com.elmakers.mine.bukkit.block.MaterialAndData;
import com.elmakers.mine.bukkit.magic.MagicController;
import com.elmakers.mine.bukkit.utility.CompatibilityUtils;
import com.elmakers.mine.bukkit.utility.ConfigurationUtils;
import com.elmakers.mine.bukkit.utility.NMSUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.HorseJumpEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;

import com.elmakers.mine.bukkit.api.spell.SpellEventType;
import com.elmakers.mine.bukkit.api.spell.SpellResult;
import com.elmakers.mine.bukkit.spell.TargetingSpell;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Set;

public class LevitateSpell extends TargetingSpell implements Listener
{
	private static final float defaultFlySpeed = 0.1f;
	
	private long levitateEnded;
	private final long safetyLength = 10000;

    private final static int minRingEffectRange = 1;
    private final static int maxRingEffectRange = 8;
    private final static int maxDamageAmount = 150;
    
    private float flySpeed = 0;
    private int flyDelay = 2;
    private int startDelay = 0;

    private int autoDeactivateHeight = 0;
    private int boostTicksRemaining = 0;

    private double castBoost = 0;
    private int boostTicks = 0;
    private double yBoost = 2;
    private float thrustSpeed = 0;
    private int thrustFrequency = 1;
    private ThrustAction thrust;
    private double crashDistance = 0;
    private double slowMultiplier = 1;

    private Material mountItem = null;
    private EntityType mountType = null;
    private LivingEntity mountEntity = null;
    private double maxMountBoost = 1;
    private double mountHealth = 8;
    private int mountBoostTicks = 80;
    private boolean mountInvisible = true;
    private int forceSneak = 0;
    private CreatureSpawnEvent.SpawnReason mountSpawnReason = CreatureSpawnEvent.SpawnReason.CUSTOM;

    private boolean stashItem = false;
    private ItemStack heldItem = null;
    private int heldItemSlot = 0;

    private int mountBoostTicksRemaining = 0;

    private static LevitateListener listener = null;

    private Collection<PotionEffect> crashEffects;

    private class ThrustAction implements Runnable
    {
        private final LevitateSpell spell;
        private final int taskId;

        public ThrustAction(LevitateSpell spell, int delay, int interval)
        {
            Plugin plugin = spell.getMage().getController().getPlugin();
            this.spell = spell;
            BukkitScheduler scheduler = Bukkit.getScheduler();
            taskId = scheduler.scheduleSyncRepeatingTask(plugin, this, delay, interval);
        }

        public void stop()
        {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        public void run()
        {
            if (!spell.checkActive())
            {
                return;
            }
            Entity entity = spell.getMage().getEntity();
            if (entity == null || entity.isDead())
            {
                spell.land();
                return;
            }
            if (entity instanceof Player && !((Player)entity).isOnline()) {
                spell.land();
                return;
            }

            spell.thrust();
        }
    }

    private class LevitateListener implements Listener
    {
        private final MageController controller;

        public LevitateListener(MageController controller)
        {
            this.controller = controller;
        }

        @EventHandler
        public void onHorseJump(HorseJumpEvent event)
        {
            Entity horse = event.getEntity();
            if (horse.hasMetadata("broom"))
            {
                Entity passenger = horse.getPassenger();
                Mage mage = controller.getMage(passenger);
                Set<Spell> active = mage.getActiveSpells();
                for (Spell spell : active) {
                    if (spell instanceof LevitateSpell) {
                        LevitateSpell levitate = (LevitateSpell)spell;
                        levitate.boost(event.getPower());
                    }
                }
            }
        }

        @EventHandler
        public void onVehicleExit(VehicleExitEvent event)
        {
            Entity vehicle = event.getVehicle();
            if (vehicle.hasMetadata("broom"))
            {
                event.setCancelled(true);
                Entity passenger = vehicle.getPassenger();
                Mage mage = controller.getMage(passenger);
                Set<Spell> active = mage.getActiveSpells();
                for (Spell spell : active) {
                    if (spell instanceof LevitateSpell) {
                        LevitateSpell levitate = (LevitateSpell)spell;
                        levitate.forceSneak(10);
                    }
                }
            }
        }
    }

    protected void thrust()
    {
        if (thrustSpeed == 0) return;
        Entity entity = mage.getEntity();
        if (entity == null) return;

        boolean checkHeight = autoDeactivateHeight > 0;
        if (checkHeight && mage.isPlayer()) {
            checkHeight = mage.getPlayer().isSneaking();
        }
        if (checkHeight) {
            int height = 0;
            Block block = entity.getLocation().getBlock();
            while (height < autoDeactivateHeight && block.getType() == Material.AIR)
            {
                block = block.getRelative(BlockFace.DOWN);
                height++;
            }

            if (height < autoDeactivateHeight)
            {
                land();
                return;
            }
        }
        Vector direction = entity.getLocation().getDirection();
        direction.normalize();

        if (crashDistance > 0)
        {
            Vector threshold = direction.clone().multiply(crashDistance);
            if (checkForCrash(mage.getEyeLocation(), threshold)) return;
            if (checkForCrash(mage.getLocation(), threshold)) return;
        }

        double boost = thrustSpeed;
        if (mage.getPlayer().isSneaking() || forceSneak > 0) {
            boost *= slowMultiplier;
            forceSneak--;
        }
        else if (mountBoostTicksRemaining > 0 && mountBoostTicks > 0) {
            boost += (maxMountBoost * (mountBoostTicksRemaining / mountBoostTicks));
            --mountBoostTicksRemaining;
            updateMountHealth();
        }
        else if (boostTicksRemaining > 0) {
            boost += castBoost;
            --boostTicksRemaining;
        }
        direction.multiply(boost);
        if (mountEntity != null) {
            mountEntity.setVelocity(direction);
        } else {
            entity.setVelocity(direction);
        }
    }

    protected boolean checkForCrash(Location source, Vector threshold)
    {
        Block facingBlock = source.getBlock();
        Block targetBlock = source.add(threshold).getBlock();
        if (!targetBlock.equals(facingBlock) && targetBlock.getType() != Material.AIR) {
            deactivate(true, false);
            sendMessage(getMessage("crash"));
            mage.deactivateAllSpells();
            playEffects("crash");
            LivingEntity livingEntity = mage.getLivingEntity();
            if (crashEffects != null && livingEntity != null && crashEffects.size() > 0) {
                CompatibilityUtils.applyPotionEffects(livingEntity, crashEffects);
            }
            return true;
        }

        return false;
    }

    protected boolean checkActive()
    {
        if (!isActive()) return false;

        Entity entity = mage.getEntity();
        if (entity == null || entity.isDead()) return false;
        if (entity instanceof Player && !((Player)entity).isOnline()) return false;

        return true;
    }

	@Override
	public SpellResult onCast(ConfigurationSection parameters) 
	{
        Player player = mage.getPlayer();
        if (player == null) {
            return SpellResult.PLAYER_REQUIRED;
        }

        startDelay = parameters.getInt("start_delay", 0);
        slowMultiplier = parameters.getDouble("slow", 1);
        castBoost = parameters.getDouble("boost", 0);
        yBoost = parameters.getDouble("y_boost", 2);
		flySpeed = (float)parameters.getDouble("speed", 0);
        thrustSpeed = (float)parameters.getDouble("thrust", 0);
        thrustFrequency = parameters.getInt("thrust_interval", thrustFrequency);
        autoDeactivateHeight = parameters.getInt("auto_deactivate", 0);
        boostTicks = parameters.getInt("boost_ticks", 1);
        crashDistance = parameters.getDouble("crash_distance", 0);
        if (parameters.contains("mount_item")) {
            mountItem = ConfigurationUtils.getMaterial(parameters, "mount_item");
        } else {
            mountItem = null;
        }

        maxMountBoost = parameters.getDouble("mount_boost", 1);
        mountBoostTicks = parameters.getInt("mount_boost_ticks", 40);
        mountHealth = parameters.getDouble("mount_health", 2);
        mountInvisible = parameters.getBoolean("mount_invisible", true);
        stashItem = parameters.getBoolean("stash_item", false);

        if (parameters.contains("mount_reason")) {
            String reasonText = parameters.getString("mount_reason").toUpperCase();
            try {
                mountSpawnReason = CreatureSpawnEvent.SpawnReason.valueOf(reasonText);
            } catch (Exception ex) {
                mage.sendMessage("Unknown spawn reason: " + reasonText);
                return SpellResult.FAIL;
            }
        }

        if (parameters.contains("mount_type")) {
            try {
                String entityType = parameters.getString("mount_type");
                mountType = EntityType.valueOf(entityType.toUpperCase());
            } catch (Exception ex) {
                ex.printStackTrace();
                return SpellResult.FAIL;
            }
        } else {
            mountType = null;
        }

        crashEffects = getPotionEffects(parameters);

        thrustSpeed *= mage.getRadiusMultiplier();
        castBoost *= mage.getRadiusMultiplier();
        maxMountBoost *= mage.getRadiusMultiplier();

		if (isActive()) {
            if (castBoost != 0) {
                boostTicksRemaining += boostTicks;
                return SpellResult.AREA;
            } else if (mountEntity != null && thrust != null) {
                return SpellResult.COST_FREE;
            }
            land();
			return SpellResult.COST_FREE;
		}
		activate();

		return SpellResult.CAST;
	}

    public void boost(double amount)
    {
        if (maxMountBoost > 0 && mountBoostTicks > 0) {
            if (mountBoostTicksRemaining == 0) {
                playEffects("boost");
            }
            mountBoostTicksRemaining = (int)Math.min((double)mountBoostTicksRemaining + (double)mountBoostTicks * amount, mountBoostTicks);
            updateMountHealth();
        }
    }

    protected void updateMountHealth() {
        if (mountEntity != null && mountBoostTicks > 0) {
            double maxHealth = mountEntity.getMaxHealth();
            double health = Math.min(0.5 + maxHealth * mountBoostTicksRemaining / mountBoostTicks, maxHealth);
            mountEntity.setHealth(health);
        }
    }

    public void land() {
        deactivate(true, false);

        // Visual effect
        playEffects("land", minRingEffectRange);
    }

	@Override
	public void onDeactivate() {
        if (thrust != null) {
            thrust.stop();
            thrust = null;
        }
        Entity mageEntity = mage.getEntity();
        if (mountEntity != null) {
            if (mageEntity != null) {
                mageEntity.eject();
            }
            mountEntity.eject();
            mountEntity.setPassenger(null);
            Plugin plugin = controller.getPlugin();
            mountEntity.removeMetadata("notarget", plugin);
            mountEntity.removeMetadata("broom", plugin);
            CompatibilityUtils.setInvulnerable(mountEntity, false);
            mountEntity.setHealth(0);
            mountEntity.remove();
            mountEntity = null;
        }
        final Player player = mage.getPlayer();
        if (player == null) return;
		
		if (flySpeed > 0) {
			player.setFlySpeed(defaultFlySpeed);
		}

        if (heldItem != null) {
            PlayerInventory inventory = player.getInventory();
            ItemStack current = inventory.getItem(heldItemSlot);
            inventory.setItem(heldItemSlot, heldItem);
            if (current != null && current.getType() != Material.AIR) {
                controller.giveItemToPlayer(player, current);
            }
            heldItem = null;
        }
		
		player.setFlying(false);
		player.setAllowFlight(false);

		levitateEnded = System.currentTimeMillis();
	}
	
	@Override
	public void onActivate() {
		final Player player = mage.getPlayer();
		if (player == null) return;

        // Prevent the player from death by fall
        levitateEnded = 0;
        mage.registerEvent(SpellEventType.PLAYER_DAMAGE, this);

        if (stashItem) {
            PlayerInventory inventory = player.getInventory();
            heldItemSlot = inventory.getHeldItemSlot();
            heldItem = inventory.getItemInHand();
            inventory.setItemInHand(null);
        } else {
            heldItem = null;
        }

		if (flySpeed > 0) {
			player.setFlySpeed(flySpeed * defaultFlySpeed);
		}

        if (thrustSpeed > 0) {
            if (thrust != null) {
                thrust.stop();
            }
            thrust = new ThrustAction(this, thrustFrequency + flyDelay + startDelay, thrustFrequency);
        }

        if (mountType != null) {
            Location location = mage.getLocation();
            World world = location.getWorld();
            Entity entity = null;
            try {
                Class<?> mountClass = NMSUtils.getBukkitClass("net.minecraft.server." + mountType.getName());
                if (mountClass != null) {
                    final Class<?> worldClass = NMSUtils.getBukkitClass("net.minecraft.server.World");
                    final Class<?> entityClass = NMSUtils.getBukkitClass("net.minecraft.server.Entity");
                    Constructor<? extends Object> constructor = mountClass.getConstructor(worldClass);
                    Object nmsWorld = NMSUtils.getHandle(world);
                    Object nmsEntity = constructor.newInstance(nmsWorld);
                    entity = NMSUtils.getBukkitEntity(nmsEntity);
                    if (entity != null) {
                        if (entity instanceof LivingEntity && mountInvisible) {
                            ((LivingEntity)entity).addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 2 << 24, 0));
                        }
                        Method setLocationMethod = mountClass.getMethod("setLocation", Double.TYPE, Double.TYPE, Double.TYPE, Float.TYPE, Float.TYPE);
                        setLocationMethod.invoke(nmsEntity, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                        Method addEntityMethod = worldClass.getMethod("addEntity", entityClass, CreatureSpawnEvent.SpawnReason.class);
                        addEntityMethod.invoke(nmsWorld, nmsEntity, mountSpawnReason);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            if (entity != null && entity instanceof LivingEntity) {
                mountEntity = (LivingEntity)entity;
                CompatibilityUtils.setInvulnerable(mountEntity);

                if (entity instanceof Horse) {
                    Horse horse = (Horse) mountEntity;
                    horse.setTamed(true);
                    horse.setOwner(player);
                    horse.setAdult();
                    horse.setStyle(Horse.Style.NONE);
                    horse.setVariant(Horse.Variant.HORSE);
                    horse.getInventory().setSaddle(new ItemStack(Material.SADDLE, 1));
                    if (mountItem != null) {
                        horse.getInventory().setArmor(new ItemStack(mountItem, 1));
                    }
                }
                if (entity instanceof Pig) {
                    Pig pig = (Pig) entity;
                    pig.setSaddle(true);
                }
                mountEntity.setHealth(0.5);
                mountEntity.setMaxHealth(mountHealth);
                mountEntity.setPassenger(mage.getEntity());

                mountEntity.setMetadata("notarget", new FixedMetadataValue(controller.getPlugin(), true));
                mountEntity.setMetadata("broom", new FixedMetadataValue(controller.getPlugin(), true));

                if (listener == null) {
                    listener = new LevitateListener(controller);
                    Plugin plugin = controller.getPlugin();
                    plugin.getServer().getPluginManager().registerEvents(listener, plugin);
                }
            }
        }
		
		Vector velocity = player.getVelocity();
		velocity.setY(velocity.getY() + yBoost);
        if (mountEntity != null) {
            mountEntity.setVelocity(velocity);
        } else {
            player.setVelocity(velocity);
        }
		Bukkit.getScheduler().scheduleSyncDelayedTask(controller.getPlugin(), new Runnable() {
			public void run() {
				player.setAllowFlight(true);
				player.setFlying(true);
			}
		}, flyDelay);
	}

	@SuppressWarnings("deprecation")
	@Override
	@EventHandler
	public void onPlayerDamage(EntityDamageEvent event)
	{
		if (event.getCause() != DamageCause.FALL) return;

		if (levitateEnded == 0 || levitateEnded + safetyLength > System.currentTimeMillis())
		{
			event.setCancelled(true);

			// Visual effect
            int ringEffectRange = (int)Math.ceil(((double)maxRingEffectRange - minRingEffectRange) * event.getDamage() / maxDamageAmount + minRingEffectRange);
            ringEffectRange = Math.min(maxRingEffectRange, ringEffectRange);
            playEffects("land", ringEffectRange);
		}

        if (levitateEnded != 0 && System.currentTimeMillis() > levitateEnded + safetyLength) {
            mage.unregisterEvent(SpellEventType.PLAYER_DAMAGE, this);
        }
    }

    public void forceSneak(int ticks) {
        forceSneak = ticks;
    }

    @Override
    public com.elmakers.mine.bukkit.api.block.MaterialAndData getEffectMaterial()
    {
        Block block = mage.getEntity().getLocation().getBlock();
        block = block.getRelative(BlockFace.DOWN);
        return new MaterialAndData(block);
    }
}
