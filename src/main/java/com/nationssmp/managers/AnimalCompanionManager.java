package com.nationssmp.managers;

import com.nationssmp.NationsSMP;
import com.nationssmp.data.Nation;
import com.nationssmp.data.NationAnimal;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the nation animal companion for each player.
 * One companion per player; always present, balanced stats, fights alongside master.
 */
public class AnimalCompanionManager {

    private final NationsSMP plugin;
    private final NationManager nationManager;

    /** uuid → NPC id of companion */
    private final Map<String, Integer> companions = new ConcurrentHashMap<>();

    public AnimalCompanionManager(NationsSMP plugin, NationManager nationManager) {
        this.plugin = plugin;
        this.nationManager = nationManager;
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────

    public void spawnCompanion(Player player, Nation nation) {
        if (nation.getAnimalKey() == null) return;
        despawnCompanion(player.getUniqueId());

        NationAnimal animal = NationAnimal.byKey(nation.getAnimalKey());
        NPCRegistry registry = CitizensAPI.getNPCRegistry();

        String npcName = ChatColor.YELLOW + animal.getEmoji() + " " + nation.getNationName()
            + "'s " + animal.getDisplayName();

        NPC npc = registry.createNPC(animal.getEntityType(), npcName);
        npc.data().set("companion_owner", player.getUniqueId().toString());
        npc.data().set("companion_animal", animal.getKey());

        try {
            Location loc = player.getLocation().clone().add(1.5, 0, 1.5);
            npc.spawn(loc);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not spawn companion for " + player.getName() + ": " + e.getMessage());
            return;
        }

        // Apply stats
        if (npc.getEntity() instanceof LivingEntity le) {
            applyCompanionStats(le);
        }

        companions.put(player.getUniqueId().toString(), npc.getId());
        nation.setCompanionNpcId(npc.getId());
        nationManager.saveNation(nation);

        // Start companion AI
        startCompanionAI(player, npc);
    }

    private void applyCompanionStats(LivingEntity entity) {
        double hp = plugin.getConfig().getDouble("companion-health", 100.0);
        try {
            var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(hp);
                entity.setHealth(hp);
            }
        } catch (Exception ignored) {}
    }

    /** uuid → running AI task id */
    private final Map<String, BukkitTask> aiTasks = new ConcurrentHashMap<>();

    private void startCompanionAI(Player player, NPC npc) {
        // Cancel any previous task for this player
        BukkitTask old = aiTasks.remove(player.getUniqueId().toString());
        if (old != null) old.cancel();

        double dmg = plugin.getConfig().getDouble("companion-damage", 6.0);
        BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                // Stop if player offline or NPC destroyed
                if (!player.isOnline()) { cancel(); return; }
                if (!CitizensAPI.getNPCRegistry().isNPC(npc.getEntity() != null ? npc.getEntity() : null)
                        && !npc.isSpawned()) {
                    // NPC was destroyed (player despawned) — stop the loop
                    cancel();
                    return;
                }
                if (!npc.isSpawned()) {
                    // Respawn if entity died in-world
                    try {
                        npc.spawn(player.getLocation());
                        if (npc.getEntity() instanceof LivingEntity le) applyCompanionStats(le);
                    } catch (Exception ignored) {}
                    return;
                }
                Entity entity = npc.getEntity();
                if (entity == null) return;

                // Follow player
                double dist = entity.getLocation().distance(player.getLocation());
                if (dist > 50) {
                    entity.teleport(player.getLocation().clone().add(2, 0, 0));
                } else if (dist > 4) {
                    npc.getNavigator().setTarget(player);
                }

                // Attack nearby hostiles
                for (Entity nearby : entity.getNearbyEntities(8, 8, 8)) {
                    if (nearby instanceof Monster || nearby instanceof Slime) {
                        npc.getNavigator().setTarget(nearby.getLocation());
                        ((LivingEntity) nearby).damage(dmg, entity);
                        break;
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
        aiTasks.put(player.getUniqueId().toString(), task);
    }

    public void despawnCompanion(UUID playerUUID) {
        String uid = playerUUID.toString();
        // Cancel AI task first
        BukkitTask task = aiTasks.remove(uid);
        if (task != null) task.cancel();
        Integer npcId = companions.remove(uid);
        if (npcId == null) return;
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null) npc.destroy();
    }

    public void respawnCompanion(Player player) {
        Nation n = nationManager.getNation(player.getUniqueId());
        if (n == null || !n.isSetupComplete()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawnCompanion(player, n), 40L);
    }

    /** Whether the given entity is a companion NPC (not a player bot). */
    public boolean isCompanion(Entity entity) {
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
            return npc != null && npc.data().has("companion_owner");
        } catch (Exception e) { return false; }
    }
}
