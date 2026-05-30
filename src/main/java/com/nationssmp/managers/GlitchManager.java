package com.nationssmp.managers;

import com.nationssmp.NationsSMP;
import com.nationssmp.data.Nation;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Manages the GLITCH NPC and the Obsidian Sword 50-hour death timer.
 *
 * Flow:
 *  1. Obsidian Sword sits in an Ancient City chest — timer not running.
 *  2. A player picks it up → lootTime is stamped in glitch.yml → GLITCH spawns.
 *  3. Every tick GLITCH chases the current sword-holder.
 *  4. At exactly 50 hours after lootTime → holder is killed, sword vanishes,
 *     GLITCH despawns.
 *  5. If sword is passed to another player GLITCH switches target.
 *  6. If sword sits in a chest (no player has it in inventory) the timer still
 *     counts — only a chest that was never opened pauses it. Once looted, the
 *     clock cannot be stopped.
 */
public class GlitchManager {

    private static final long FIFTY_HOURS_MS = 50L * 60L * 60L * 1000L;

    private final NationsSMP plugin;
    private final NationManager nationManager;
    private final LegendaryItemManager legendaryItemManager;

    private FileConfiguration glitchConfig;

    private boolean timerActive = false;
    private long lootTimeMs     = 0;
    private String currentHolderUUID = null; // uuid of player currently holding the sword
    private int glitchNpcId     = -1;

    private BukkitRunnable mainTask;
    private BukkitRunnable scanTask; // scans all online players for sword in inventory

    public GlitchManager(NationsSMP plugin, NationManager nationManager,
                         LegendaryItemManager legendaryItemManager) {
        this.plugin = plugin;
        this.nationManager = nationManager;
        this.legendaryItemManager = legendaryItemManager;
        this.glitchConfig = plugin.getDataManager().getGlitchConfig();
        loadState();
        startScanTask();
        if (timerActive) startMainTask();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadState() {
        timerActive       = glitchConfig.getBoolean("active", false);
        lootTimeMs        = glitchConfig.getLong("lootTime", 0);
        currentHolderUUID = glitchConfig.getString("holderUUID");
        glitchNpcId       = glitchConfig.getInt("glitchNpcId", -1);
    }

    private void saveState() {
        glitchConfig.set("active",      timerActive);
        glitchConfig.set("lootTime",    lootTimeMs);
        glitchConfig.set("holderUUID",  currentHolderUUID);
        glitchConfig.set("glitchNpcId", glitchNpcId);
        plugin.getDataManager().saveGlitch();
    }

    // ── Trigger (called when Obsidian Sword is picked up for first time) ───────

    public void onSwordFirstLooted(Player player) {
        if (timerActive) return; // already running
        timerActive       = true;
        lootTimeMs        = System.currentTimeMillis();
        currentHolderUUID = player.getUniqueId().toString();
        saveState();
        spawnGlitch(player.getLocation());
        startMainTask();

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓");
        Bukkit.broadcastMessage(ChatColor.GRAY + "  THE OBSIDIAN SWORD HAS BEEN LOOTED...");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "        G̶L̶I̶T̶C̶H̶  A̶W̶A̶K̶E̶N̶S̶.");
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓");
        Bukkit.broadcastMessage("");
    }

    // ── Transfer target when sword changes hands ──────────────────────────────

    public void onSwordTransferred(Player newHolder) {
        currentHolderUUID = newHolder.getUniqueId().toString();
        saveState();
        teleportGlitchToPlayer(newHolder);
        newHolder.sendMessage(ChatColor.DARK_GRAY + "You feel something watching you...");
    }

    // ── Scan task – every 2 seconds checks who has the sword ─────────────────

    private void startScanTask() {
        scanTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!timerActive) return;
                Player holder = findSwordHolder();
                String newUID = holder != null ? holder.getUniqueId().toString() : null;
                if (newUID != null && !newUID.equals(currentHolderUUID)) {
                    currentHolderUUID = newUID;
                    saveState();
                    teleportGlitchToPlayer(holder);
                }
            }
        };
        scanTask.runTaskTimer(plugin, 40L, 40L);
    }

    /** Find which online player is holding the Obsidian Sword. */
    private Player findSwordHolder() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : p.getInventory().getContents()) {
                if (legendaryItemManager.isObsidianSword(item)) return p;
            }
        }
        return null;
    }

    // ── Main countdown task ───────────────────────────────────────────────────

    private void startMainTask() {
        mainTask = new BukkitRunnable() {
            long lastWarningCheck = 0;

            @Override
            public void run() {
                if (!timerActive) { cancel(); return; }

                long elapsed = System.currentTimeMillis() - lootTimeMs;
                long remaining = FIFTY_HOURS_MS - elapsed;

                if (remaining <= 0) {
                    triggerGlitchKill();
                    cancel();
                    return;
                }

                long hoursLeft   = remaining / (1000 * 60 * 60);
                long minutesLeft = (remaining / (1000 * 60)) % 60;

                // Chase current holder
                chaseHolder();

                // Hour-based warnings at specific remaining-hour thresholds
                long checkKey = hoursLeft;
                if (checkKey != lastWarningCheck) {
                    lastWarningCheck = checkKey;
                    // Warn when exactly 45h, 48h, or 49h remain
                    int[] warnHours = {45, 48, 49};
                    for (int h : warnHours) {
                        if (hoursLeft == h) {
                            broadcastWarning(hoursLeft, minutesLeft);
                        }
                    }
                    // Final 5 hours: warn every hour
                    if (hoursLeft <= 5 && hoursLeft > 0) broadcastWarning(hoursLeft, minutesLeft);
                }

                // Final 30 minutes: warn every minute
                if (remaining <= 30L * 60 * 1000 && remaining % (60 * 1000) < 1000) {
                    broadcastWarning(hoursLeft, minutesLeft);
                }
            }
        };
        mainTask.runTaskTimer(plugin, 20L, 20L); // every second
    }

    private void broadcastWarning(long hours, long minutes) {
        String time = hours > 0 ? hours + "h " + minutes + "m" : minutes + " minutes";
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "☠ GLITCH: " + ChatColor.GRAY
            + time + " remain before the sword's holder is claimed...");
    }

    // ── Glitch NPC chase logic ────────────────────────────────────────────────

    private void chaseHolder() {
        if (currentHolderUUID == null) return;
        Player holder;
        try { holder = Bukkit.getPlayer(UUID.fromString(currentHolderUUID)); }
        catch (Exception e) { return; }
        if (holder == null || !holder.isOnline()) return;

        NPC glitch = getGlitchNpc();
        if (glitch == null) {
            spawnGlitch(holder.getLocation());
            return;
        }
        if (!glitch.isSpawned()) {
            try { glitch.spawn(holder.getLocation()); } catch (Exception ignored) {}
            return;
        }
        Entity glitchEntity = glitch.getEntity();
        if (glitchEntity == null) return;

        double dist = glitchEntity.getLocation().distance(holder.getLocation());
        if (dist > 60) {
            glitchEntity.teleport(holder.getLocation().clone().add(3, 0, 3));
        } else if (dist > 3) {
            glitch.getNavigator().setTarget(holder);
        }

        // Make Glitch emit particles and sound to nearby players
        holder.getWorld().spawnParticle(Particle.SPELL_WITCH, glitchEntity.getLocation(), 5, 0.5, 0.5, 0.5);
    }

    private void teleportGlitchToPlayer(Player player) {
        NPC glitch = getGlitchNpc();
        if (glitch == null) { spawnGlitch(player.getLocation()); return; }
        if (glitch.isSpawned() && glitch.getEntity() != null) {
            glitch.getEntity().teleport(player.getLocation().clone().add(3, 0, 3));
        }
    }

    // ── Kill at hour 50 ───────────────────────────────────────────────────────

    private void triggerGlitchKill() {
        Player holder = findSwordHolder();

        // If no one has it in inventory, kill the last known holder if online
        if (holder == null && currentHolderUUID != null) {
            try { holder = Bukkit.getPlayer(UUID.fromString(currentHolderUUID)); }
            catch (Exception ignored) {}
        }

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓");
        if (holder != null && holder.isOnline()) {
            Nation n = nationManager.getNation(holder.getUniqueId());
            String nationName = n != null ? n.getNationName() : holder.getName();
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "  GLITCH HAS CLAIMED "
                + holder.getName().toUpperCase() + " OF " + nationName.toUpperCase());
            Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "  The sword vanishes. Silence falls.");

            // Remove sword from inventory
            var inv = holder.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (legendaryItemManager.isObsidianSword(inv.getItem(i))) {
                    inv.setItem(i, null);
                }
            }
            // Kill the player
            holder.setHealth(0);
        } else {
            Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "  GLITCH finds no host. The sword dissolves.");
        }
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓");

        // Reset state
        despawnGlitch();
        timerActive       = false;
        lootTimeMs        = 0;
        currentHolderUUID = null;
        glitchNpcId       = -1;

        // Re-inject obsidian sword into a new Ancient City chest location
        // (spawn handled by LegendaryItemManager on next deep dark chest open)
        glitchConfig.set("swordRespawned", false);
        saveState();
    }

    // ── Glitch NPC ────────────────────────────────────────────────────────────

    private void spawnGlitch(Location loc) {
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        // Remove old if exists
        despawnGlitch();

        NPC glitch = registry.createNPC(EntityType.SKELETON,
            ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "G̶L̶I̶T̶C̶H̶");
        glitch.data().set("is_glitch", true);
        try {
            glitch.spawn(loc);
            if (glitch.getEntity() instanceof LivingEntity le) {
                le.setInvulnerable(true);
                le.setSilent(false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to spawn Glitch NPC: " + e.getMessage());
        }
        glitchNpcId = glitch.getId();
        saveState();
    }

    private void despawnGlitch() {
        NPC glitch = getGlitchNpc();
        if (glitch != null) glitch.destroy();
        glitchNpcId = -1;
    }

    private NPC getGlitchNpc() {
        if (glitchNpcId == -1) return null;
        return CitizensAPI.getNPCRegistry().getById(glitchNpcId);
    }

    /** Called from entity damage listener — Glitch takes zero damage. */
    public boolean isGlitch(Entity entity) {
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
            return npc != null && npc.data().get("is_glitch", false);
        } catch (Exception e) { return false; }
    }

    public boolean isTimerActive() { return timerActive; }
    public long getLootTimeMs()    { return lootTimeMs; }
    public long getRemainingMs()   {
        if (!timerActive) return -1;
        return FIFTY_HOURS_MS - (System.currentTimeMillis() - lootTimeMs);
    }

    public void shutdown() {
        if (mainTask != null) mainTask.cancel();
        if (scanTask != null) scanTask.cancel();
    }
}
