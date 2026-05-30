package com.nationssmp.commands;

import com.nationssmp.NationsSMP;
import com.nationssmp.data.Nation;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

// ══════════════════════════════════════════════════════════════════════════════
// /givebots <nationName>
// Stages inheritance for the current player.
// ══════════════════════════════════════════════════════════════════════════════
class GivebotsCommandExecutor implements CommandExecutor {

    private final NationsSMP plugin;
    GivebotsCommandExecutor(NationsSMP p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: /givebots <nationName>");
            return true;
        }
        String targetName = args[0];
        Nation target = plugin.getNationManager().getNationByName(targetName);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Nation '" + targetName + "' does not exist.");
            return true;
        }
        Nation self = plugin.getNationManager().getNation(player.getUniqueId());
        if (self == null || self.getNationName().equalsIgnoreCase(targetName)) {
            player.sendMessage(ChatColor.RED + "You cannot give your bots to yourself.");
            return true;
        }
        plugin.getDeathListener().stageInheritance(player, targetName);
        return true;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// /I BEAR OATH TO <player> <title> OF <nation>
// ══════════════════════════════════════════════════════════════════════════════
class OathCommandExecutor implements CommandExecutor {

    private final NationsSMP plugin;
    OathCommandExecutor(NationsSMP p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        // Reassemble args (skipping "BEAR OATH TO" which are the first 3 args)
        // Plugin.yml registers "/I" so args[0]=BEAR args[1]=OATH args[2]=TO args[3...]=rest
        if (args.length < 4) {
            player.sendMessage(ChatColor.RED
                + "Usage: /I BEAR OATH TO <player> <title> OF <nation>");
            return true;
        }
        // Build the string after "BEAR OATH TO"
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < args.length; i++) {
            if (i > 3) sb.append(" ");
            sb.append(args[i]);
        }
        plugin.getOathManager().processOathCommand(player, sb.toString());
        return true;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// /nation info | /nation animals | /nation setbanner
// ══════════════════════════════════════════════════════════════════════════════
class NationCommandExecutor implements CommandExecutor {

    private final NationsSMP plugin;
    NationCommandExecutor(NationsSMP p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        Nation nation = plugin.getNationManager().getNation(player.getUniqueId());
        if (nation == null || !nation.isSetupComplete()) {
            player.sendMessage(ChatColor.RED + "Your nation is not yet established.");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "info";
        switch (sub) {
            case "info" -> {
                var animal = com.nationssmp.data.NationAnimal.byKey(nation.getAnimalKey());
                player.sendMessage(ChatColor.GOLD + "── " + nation.getNationName() + " ──");
                player.sendMessage(ChatColor.YELLOW + "Title: " + ChatColor.WHITE + nation.getTitle());
                player.sendMessage(ChatColor.YELLOW + "Motto: " + ChatColor.GRAY + "\"" + nation.getMotto() + "\"");
                player.sendMessage(ChatColor.YELLOW + "Animal: " + animal.getEmoji() + " " + animal.getDisplayName());
                player.sendMessage(ChatColor.YELLOW + "Block: " + ChatColor.WHITE + nation.getBuildingBlock());
                player.sendMessage(ChatColor.YELLOW + "Bots: " + ChatColor.WHITE + nation.getBotCount() + "/30");
                if (nation.getAllyNationName() != null)
                    player.sendMessage(ChatColor.YELLOW + "Ally: " + ChatColor.GREEN + nation.getAllyNationName());
                if (nation.isOathbreaker())
                    player.sendMessage(ChatColor.DARK_RED + "⚠ OATHBREAKER ⚠");
                if (nation.isMartyr())
                    player.sendMessage(ChatColor.GOLD + "✦ MARTYR ✦");
                player.sendMessage(ChatColor.YELLOW + "Land: " + ChatColor.WHITE
                    + nation.getClaimedChunks().size() + " chunk(s)");
            }
            case "animals" -> {
                player.sendMessage(ChatColor.GOLD + "── Available Animals ──");
                for (var a : com.nationssmp.data.NationAnimal.values()) {
                    boolean taken = plugin.getNationManager().animalTaken(a.getKey())
                        && !a.getKey().equalsIgnoreCase(nation.getAnimalKey());
                    player.sendMessage(a.getEmoji() + " " + a.getDisplayName()
                        + (taken ? ChatColor.RED + " (taken)" : ChatColor.GREEN + " (free)"));
                }
            }
            default -> player.sendMessage(ChatColor.RED + "Usage: /nation info | animals");
        }
        return true;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// /nsadmin spawnitems | resetglitch | resetthrone | spawncastle | listlands
// ══════════════════════════════════════════════════════════════════════════════
class AdminCommandExecutor implements CommandExecutor {

    private final NationsSMP plugin;
    AdminCommandExecutor(NationsSMP p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nationssmp.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("/nsadmin spawnitems | resetglitch | resetthrone | spawncastle | listlands");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawnitems" -> {
                sender.sendMessage(ChatColor.YELLOW + "Re-spawning legendary items...");
                // Reset spawned flag and re-trigger
                plugin.getDataManager().getLegendaryConfig().set("spawned", false);
                plugin.getDataManager().saveLegendary();
                sender.sendMessage(ChatColor.GREEN + "Done. Restart or reload to respawn items.");
            }
            case "resetglitch" -> {
                plugin.getDataManager().getGlitchConfig().set("active", false);
                plugin.getDataManager().getGlitchConfig().set("obsidianInjected", false);
                plugin.getDataManager().saveGlitch();
                sender.sendMessage(ChatColor.GREEN + "Glitch reset. Obsidian Sword will reappear on next deep dark chest open.");
            }
            case "resetthrone" -> {
                plugin.getDataManager().getThroneConfig().set("castleBuilt", false);
                plugin.getDataManager().saveThrone();
                sender.sendMessage(ChatColor.GREEN + "Throne reset. Castle will rebuild on next End entry.");
            }
            case "listlands" -> {
                for (var n : plugin.getNationManager().getAllNations()) {
                    sender.sendMessage(ChatColor.YELLOW + n.getNationName()
                        + ": " + n.getClaimedChunks().size() + " chunks");
                }
            }
            case "givesword" -> {
                // Give admin the obsidian sword for testing
                if (sender instanceof Player p) {
                    p.getInventory().addItem(plugin.getLegendaryItemManager().buildObsidianSword());
                    p.sendMessage(ChatColor.GREEN + "Obsidian Sword given.");
                }
            }
            case "giveoathkeeper" -> {
                if (sender instanceof Player p) {
                    p.getInventory().addItem(plugin.getLegendaryItemManager().buildOathKeeperItem());
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }
        return true;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// /bookcommand <playerName>  — use the Book of Command to kill a player
// ══════════════════════════════════════════════════════════════════════════════
class BookCommandExecutor implements CommandExecutor {

    private final NationsSMP plugin;
    BookCommandExecutor(NationsSMP p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        // Must have Book of Command in hand
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.getLegendaryItemManager().isBookOfCommand(hand)) {
            player.sendMessage(ChatColor.RED + "You must hold the Book of Command to use this.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.DARK_PURPLE + "Usage: /bookcommand <playerName>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + "Player not found or not online.");
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "You cannot use the Book of Command on yourself.");
            return true;
        }

        // Transfer all target inventory to killer
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null) player.getInventory().addItem(item);
        }
        target.getInventory().clear();

        // Kill target
        target.setHealth(0);

        // Consume the book
        hand.setAmount(0);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        // Broadcast
        Nation casterNation = plugin.getNationManager().getNation(player.getUniqueId());
        Nation targetNation  = plugin.getNationManager().getNation(target.getUniqueId());
        String casterStr = casterNation != null ? casterNation.getNationName() : player.getName();
        String targetStr  = targetNation  != null ? targetNation.getNationName()  : target.getName();

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "📖 THE BOOK OF COMMAND HAS SPOKEN!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + player.getName() + " OF " + casterStr.toUpperCase()
            + ChatColor.WHITE + " has condemned "
            + ChatColor.RED + target.getName() + " OF " + targetStr.toUpperCase() + "!");
        Bukkit.broadcastMessage(ChatColor.GRAY + "The Book vanishes into ash.");
        Bukkit.broadcastMessage("");

        return true;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// /summondragon  — throne owner summons the dragon
// ══════════════════════════════════════════════════════════════════════════════
class SummonDragonCommandExecutor implements CommandExecutor {

    private final NationsSMP plugin;
    SummonDragonCommandExecutor(NationsSMP p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        plugin.getDragonManager().summonDragonForPlayer(player);
        return true;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Public wrapper class that registers all commands via plugin.yml registrations
// ══════════════════════════════════════════════════════════════════════════════
public class CommandRegistrar {

    public static void registerAll(NationsSMP plugin) {
        plugin.getCommand("army")        .setExecutor(new ArmyCommand(plugin));
        plugin.getCommand("army")        .setTabCompleter(new ArmyCommand(plugin));
        plugin.getCommand("givebots")    .setExecutor(new GivebotsCommandExecutor(plugin));
        plugin.getCommand("nation")      .setExecutor(new NationCommandExecutor(plugin));
        plugin.getCommand("nsadmin")     .setExecutor(new AdminCommandExecutor(plugin));
        plugin.getCommand("bookcommand") .setExecutor(new BookCommandExecutor(plugin));
        plugin.getCommand("summondragon").setExecutor(new SummonDragonCommandExecutor(plugin));
        plugin.getCommand("I")           .setExecutor(new OathCommandExecutor(plugin));
    }
}
