package com.nationssmp.managers;

import com.nationssmp.NationsSMP;
import com.nationssmp.data.BotRole;
import com.nationssmp.data.Nation;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all player bot NPCs.
 * Each player gets up to 30 NPCs (Citizens2 player-type NPCs).
 * Bots follow their master, attack enemies near them, and obey /army commands.
 */
public class BotManager {

    private final NationsSMP plugin;
    private final NationManager nationManager;

    /** uuid → list of active NPC ids */
    private final Map<String, List<Integer>> playerBots = new ConcurrentHashMap<>();

    /** NPC id → player uuid (master) */
    private final Map<Integer, String> botOwner = new ConcurrentHashMap<>();

    /** NPC id → assigned role */
    private final Map<Integer, BotRole> botRoles = new ConcurrentHashMap<>();

    /** Players whose bots are in "follow" mode (default) */
    private final Set<String> followMode = ConcurrentHashMap.newKeySet();

    /** Players whose bots are in "stay" mode */
    private final Set<String> stayMode = ConcurrentHashMap.newKeySet();

    /** Players whose bots are in "attack" mode */
    private final Set<String> attackMode = ConcurrentHashMap.newKeySet();

    private BukkitRunnable aiTask;

    public BotManager(NationsSMP plugin, NationManager nationManager) {
        this.plugin = plugin;
        this.nationManager = nationManager;
        startAILoop();
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────

    /** Spawn fresh bots for a player (first join or respawn). */
    public void spawnBotsForPlayer(Player player, Nation nation) {
        despawnBotsForPlayer(player.getUniqueId());

        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        List<Integer> ids = new ArrayList<>();
        int count = nation.getBotCount();

        // Default role distribution: 10 soldiers, 5 archers, 5 miners, 5 guards, 5 unassigned (soldier)
        BotRole[] defaults = new BotRole[count];
        Arrays.fill(defaults, BotRole.SOLDIER);
        if (count >= 10) {
            for (int i = 5; i < 10; i++)  defaults[i] = BotRole.ARCHER;
            for (int i = 10; i < 15; i++) defaults[i] = BotRole.MINER;
            for (int i = 15; i < 20; i++) defaults[i] = BotRole.GUARD;
            for (int i = 20; i < 25; i++) defaults[i] = BotRole.FARMER;
            for (int i = 25; i < count; i++) defaults[i] = BotRole.BUILDER;
        }

        Location base = player.getLocation();

        for (int i = 0; i < count; i++) {
            BotRole role = i < defaults.length ? defaults[i] : BotRole.SOLDIER;

            Location spawnLoc = base.clone().add(
                (Math.random() - 0.5) * 4, 0, (Math.random() - 0.5) * 4);

            NPC npc = registry.createNPC(EntityType.PLAYER,
                ChatColor.GRAY + "[" + role.getIcon() + "] " + role.getDisplayName());
            npc.data().set("owner", player.getUniqueId().toString());
            npc.data().set("role", role.name());

            try {
                npc.spawn(spawnLoc);
                equipNPC(npc, role);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to spawn bot " + i + " for " + player.getName());
                continue;
            }

            int npcId = npc.getId();

            // Override role from saved data if available (keyed by npcId in persistence)
            String savedRoleName = nation.getBotRoles().get(npcId);
            if (savedRoleName != null) {
                BotRole saved = BotRole.fromString(savedRoleName);
                if (saved != null) {
                    role = saved;
                    npc.setName(ChatColor.GRAY + "[" + role.getIcon() + "] " + role.getDisplayName());
                    equipNPC(npc, role);
                }
            }

            ids.add(npcId);
            botOwner.put(npcId, player.getUniqueId().toString());
            botRoles.put(npcId, role);
            nation.getBotRoles().put(npcId, role.name());
        }

        playerBots.put(player.getUniqueId().toString(), ids);
        followMode.add(player.getUniqueId().toString());
        nationManager.saveNation(nation);
        player.sendMessage(ChatColor.GREEN + "⚔ " + count + " bots have been summoned to your side!");
    }

    /** Despawn all bots for a player (on death or cleanup). */
    public void despawnBotsForPlayer(UUID playerUUID) {
        String uid = playerUUID.toString();
        List<Integer> ids = playerBots.remove(uid);
        if (ids == null) return;
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        for (int id : ids) {
            NPC npc = registry.getById(id);
            if (npc != null) npc.destroy();
            botOwner.remove(id);
            botRoles.remove(id);
        }
        followMode.remove(uid);
        stayMode.remove(uid);
        attackMode.remove(uid);
    }

    private void equipNPC(NPC npc, BotRole role) {
        if (!(npc.getEntity() instanceof Player)) return;
        Player entity = (Player) npc.getEntity();
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) return;
        Material[] mats = role.getEquipment();
        if (mats.length > 0) eq.setItemInMainHand(new ItemStack(mats[0]));
        if (mats.length > 1) eq.setChestplate(new ItemStack(mats[1]));
        if (mats.length > 2) eq.setLeggings(new ItemStack(mats[2]));
        if (mats.length > 3) eq.setHelmet(new ItemStack(mats[3]));
    }

    // ── AI Loop ───────────────────────────────────────────────────────────────

    private void startAILoop() {
        int ticks = plugin.getConfig().getInt("bot-ai-tick", 20);
        aiTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<String, List<Integer>> entry : playerBots.entrySet()) {
                    String uid = entry.getKey();
                    Player master = Bukkit.getPlayer(UUID.fromString(uid));
                    if (master == null || !master.isOnline()) continue;

                    for (int npcId : entry.getValue()) {
                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null || !npc.isSpawned()) {
                            tryRespawnBot(npcId, master);
                            continue;
                        }
                        Entity entity = npc.getEntity();
                        if (entity == null) continue;

                        if (followMode.contains(uid) && !stayMode.contains(uid)) {
                            followMaster(npc, master);
                        }

                        if (!stayMode.contains(uid)) {
                            attackNearbyEnemy(npc, master);
                        }
                    }
                }
            }
        };
        aiTask.runTaskTimer(plugin, 20L, ticks);
    }

    private void followMaster(NPC npc, Player master) {
        double maxDist = plugin.getConfig().getDouble("bot-max-follow-distance", 60);
        Entity entity = npc.getEntity();
        if (entity == null) return;
        double dist = entity.getLocation().distance(master.getLocation());
        if (dist > maxDist) {
            // Teleport if too far
            entity.teleport(master.getLocation().clone().add(
                (Math.random() - 0.5) * 3, 0, (Math.random() - 0.5) * 3));
        } else if (dist > 3) {
            npc.getNavigator().setTarget(master);
        }
    }

    private void attackNearbyEnemy(NPC npc, Player master) {
        double range = plugin.getConfig().getDouble("bot-combat-range", 8);
        double dmg   = plugin.getConfig().getDouble("bot-damage", 4.0);
        BotRole role = botRoles.get(npc.getId());
        if (role != null) dmg = role.getDamage();

        Entity botEntity = npc.getEntity();
        if (botEntity == null) return;

        // Find nearest hostile or enemy player
        LivingEntity target = null;
        double closest = range;
        for (Entity nearby : botEntity.getNearbyEntities(range, range, range)) {
            if (nearby instanceof Monster || nearby instanceof Slime) {
                double d = nearby.getLocation().distance(botEntity.getLocation());
                if (d < closest) { closest = d; target = (LivingEntity) nearby; }
            }
            // Also attack players from enemy nations
            if (nearby instanceof Player enemy && !enemy.equals(master)) {
                Nation myNation = nationManager.getNation(master.getUniqueId());
                Nation theirNation = nationManager.getNation(enemy.getUniqueId());
                if (myNation != null && theirNation != null) {
                    boolean allied = myNation.getAllyNationName() != null
                        && myNation.getAllyNationName().equalsIgnoreCase(theirNation.getNationName());
                    if (!allied && attackMode.contains(master.getUniqueId().toString())) {
                        double d = nearby.getLocation().distance(botEntity.getLocation());
                        if (d < closest) { closest = d; target = (LivingEntity) enemy; }
                    }
                }
            }
        }

        if (target != null) {
            npc.getNavigator().setTarget(target.getLocation());
            target.damage(dmg, botEntity);
        }
    }

    private void tryRespawnBot(int npcId, Player master) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null && !npc.isSpawned()) {
            try { npc.spawn(master.getLocation()); }
            catch (Exception ignored) {}
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    public void setFollow(Player player) {
        String uid = player.getUniqueId().toString();
        followMode.add(uid);
        stayMode.remove(uid);
        player.sendMessage(ChatColor.GREEN + "⚔ Bots are now following you.");
    }

    public void setStay(Player player) {
        String uid = player.getUniqueId().toString();
        stayMode.add(uid);
        followMode.remove(uid);
        // Stop navigator for all bots
        for (int id : getBotIds(player)) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc != null && npc.isSpawned()) npc.getNavigator().cancelNavigation();
        }
        player.sendMessage(ChatColor.YELLOW + "⚔ Bots are holding position.");
    }

    public void setAttack(Player player) {
        attackMode.add(player.getUniqueId().toString());
        player.sendMessage(ChatColor.RED + "⚔ Bots are now attacking all nearby enemies!");
    }

    public void setDefend(Player player) {
        attackMode.remove(player.getUniqueId().toString());
        stayMode.remove(player.getUniqueId().toString());
        followMode.add(player.getUniqueId().toString());
        player.sendMessage(ChatColor.AQUA + "⚔ Bots are forming a defensive perimeter.");
    }

    public void sendMine(Player player) {
        player.sendMessage(ChatColor.YELLOW + "⛏ Miner bots are heading underground to gather resources.");
        // Miner bots move downward from player position (simulated)
        for (int id : getBotIds(player)) {
            if (botRoles.get(id) != BotRole.MINER) continue;
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc == null || !npc.isSpawned()) continue;
            Location below = player.getLocation().clone().subtract(0, 5, 0);
            npc.getNavigator().setTarget(below);
        }
    }

    public boolean assignRole(Player player, int botIndex, BotRole role) {
        List<Integer> ids = getBotIds(player);
        if (botIndex < 0 || botIndex >= ids.size()) return false;
        int npcId = ids.get(botIndex);
        botRoles.put(npcId, role);
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null) {
            npc.setName(ChatColor.GRAY + "[" + role.getIcon() + "] " + role.getDisplayName());
            if (npc.isSpawned()) equipNPC(npc, role);
        }
        Nation n = nationManager.getNation(player.getUniqueId());
        if (n != null) {
            n.getBotRoles().put(npcId, role.name());
            nationManager.saveNation(n);
        }
        return true;
    }

    public void sendStatusMessage(Player player) {
        Nation n = nationManager.getNation(player.getUniqueId());
        if (n == null) return;
        player.sendMessage(ChatColor.GOLD + "── Army Status ──────────────────");
        player.sendMessage(ChatColor.YELLOW + "Bots alive: " + ChatColor.WHITE + n.getBotCount() + "/30");
        player.sendMessage(ChatColor.YELLOW + "Mode: " + ChatColor.WHITE + getCurrentMode(player));
        Map<BotRole, Integer> roleCounts = new HashMap<>();
        for (int id : getBotIds(player)) {
            BotRole r = botRoles.getOrDefault(id, BotRole.SOLDIER);
            roleCounts.merge(r, 1, Integer::sum);
        }
        roleCounts.forEach((r, c) ->
            player.sendMessage("  " + r.getIcon() + " " + r.getDisplayName() + ": " + c));
        player.sendMessage(ChatColor.GOLD + "──────────────────────────────");
    }

    public void spamTitleAndMotto(Player player) {
        Nation n = nationManager.getNation(player.getUniqueId());
        if (n == null) return;
        NationAnimal animalEnum = com.nationssmp.data.NationAnimal.byKey(n.getAnimalKey());
        String shout = animalEnum.getEmoji() + " ALL HAIL " + player.getName()
            + ", " + n.getTitle() + " OF " + n.getNationName().toUpperCase()
            + "! \"" + n.getMotto() + "\"";
        List<Integer> ids = getBotIds(player);
        int delay = 0;
        for (int id : ids) {
            int d = delay;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                NPC npc = CitizensAPI.getNPCRegistry().getById(id);
                if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
                    Bukkit.broadcastMessage(ChatColor.GRAY + "[Bot] " + shout);
                }
            }, d);
            delay += 1;
        }
    }

    public void sendBotList(Player player) {
        List<Integer> ids = getBotIds(player);
        player.sendMessage(ChatColor.GOLD + "── Bot List (" + ids.size() + " bots) ──");
        for (int i = 0; i < ids.size(); i++) {
            BotRole r = botRoles.getOrDefault(ids.get(i), BotRole.SOLDIER);
            player.sendMessage(ChatColor.GRAY + "#" + i + " → " + r.getIcon() + " " + r.getDisplayName());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public List<Integer> getBotIds(Player player) {
        return playerBots.getOrDefault(player.getUniqueId().toString(), Collections.emptyList());
    }

    private String getCurrentMode(Player player) {
        String uid = player.getUniqueId().toString();
        if (stayMode.contains(uid))   return "HOLD POSITION";
        if (attackMode.contains(uid)) return "ATTACK";
        return "FOLLOW";
    }

    public boolean isOwnerOfNpc(int npcId, UUID playerUUID) {
        return playerUUID.toString().equals(botOwner.get(npcId));
    }

    /** Kill a specific number of bots for a player (oathbreaker punishment). Returns bots actually killed. */
    public int killBots(Player player, int count) {
        List<Integer> ids = playerBots.getOrDefault(player.getUniqueId().toString(), new ArrayList<>());
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        int killed = 0;
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext() && killed < count) {
            int id = it.next();
            NPC npc = registry.getById(id);
            if (npc != null) {
                if (npc.isSpawned() && npc.getEntity() instanceof LivingEntity le) {
                    le.setHealth(0);
                }
                npc.destroy();
            }
            botOwner.remove(id);
            botRoles.remove(id);
            it.remove();
            killed++;
        }
        Nation n = nationManager.getNation(player.getUniqueId());
        if (n != null) {
            n.setBotCount(Math.max(0, n.getBotCount() - killed));
            nationManager.saveNation(n);
        }
        return killed;
    }

    public void shutdown() {
        if (aiTask != null) aiTask.cancel();
    }
}
