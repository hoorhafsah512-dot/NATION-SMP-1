package com.nationssmp.commands;

import com.nationssmp.NationsSMP;
import com.nationssmp.data.BotRole;
import com.nationssmp.data.Nation;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /army <subcommand>
 *
 * Subcommands:
 *   list           — list all bots and their roles
 *   assign <#> <role> — assign a role to bot by index
 *   follow         — bots follow master
 *   stay           — bots hold position
 *   attack         — bots attack all nearby enemies
 *   defend         — bots protect master (follow + no attack)
 *   mine           — miner bots go dig
 *   status         — show army stats
 *   spam           — bots shout nation title and motto
 */
public class ArmyCommand implements CommandExecutor, TabCompleter {

    private final NationsSMP plugin;

    public ArmyCommand(NationsSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }

        Nation nation = plugin.getNationManager().getNation(player.getUniqueId());
        if (nation == null || !nation.isSetupComplete()) {
            player.sendMessage(ChatColor.RED + "You have not established your nation yet.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "list":
                plugin.getBotManager().sendBotList(player);
                break;

            case "follow":
                plugin.getBotManager().setFollow(player);
                break;

            case "stay":
                plugin.getBotManager().setStay(player);
                break;

            case "attack":
                plugin.getBotManager().setAttack(player);
                break;

            case "defend":
                plugin.getBotManager().setDefend(player);
                break;

            case "mine":
                plugin.getBotManager().sendMine(player);
                break;

            case "status":
                plugin.getBotManager().sendStatusMessage(player);
                break;

            case "spam":
                plugin.getBotManager().spamTitleAndMotto(player);
                break;

            case "assign": {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /army assign <botIndex> <role>");
                    player.sendMessage(ChatColor.GRAY + "Roles: soldier, archer, mage, miner, farmer, lumberjack, guard, assassin, alchemist, scout, trader, builder");
                    return true;
                }
                int index;
                try { index = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Bot index must be a number.");
                    return true;
                }
                BotRole role = BotRole.fromString(args[2]);
                if (role == null) {
                    player.sendMessage(ChatColor.RED + "Unknown role: " + args[2]);
                    player.sendMessage(ChatColor.GRAY + "Valid roles: "
                        + String.join(", ", Arrays.stream(BotRole.values())
                            .map(r -> r.name().toLowerCase()).toList()));
                    return true;
                }
                boolean success = plugin.getBotManager().assignRole(player, index, role);
                if (success) {
                    player.sendMessage(ChatColor.GREEN + "Bot #" + index + " is now a "
                        + role.getIcon() + " " + role.getDisplayName() + ".");
                } else {
                    player.sendMessage(ChatColor.RED + "Invalid bot index. Use /army list to see bot numbers.");
                }
                break;
            }

            default:
                sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "── Army Commands ──────────────────");
        player.sendMessage(ChatColor.YELLOW + "/army list" + ChatColor.WHITE + " — view all bots");
        player.sendMessage(ChatColor.YELLOW + "/army assign <#> <role>" + ChatColor.WHITE + " — assign role to a bot");
        player.sendMessage(ChatColor.YELLOW + "/army follow" + ChatColor.WHITE + " — bots follow you");
        player.sendMessage(ChatColor.YELLOW + "/army stay" + ChatColor.WHITE + " — bots hold position");
        player.sendMessage(ChatColor.YELLOW + "/army attack" + ChatColor.WHITE + " — bots attack enemies");
        player.sendMessage(ChatColor.YELLOW + "/army defend" + ChatColor.WHITE + " — bots defend you");
        player.sendMessage(ChatColor.YELLOW + "/army mine" + ChatColor.WHITE + " — miner bots mine");
        player.sendMessage(ChatColor.YELLOW + "/army status" + ChatColor.WHITE + " — army overview");
        player.sendMessage(ChatColor.YELLOW + "/army spam" + ChatColor.WHITE + " — bots shout your title");
        player.sendMessage(ChatColor.GOLD + "──────────────────────────────────");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list","assign","follow","stay","attack","defend","mine","status","spam");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("assign")) {
            return Arrays.stream(BotRole.values())
                .map(r -> r.name().toLowerCase()).toList();
        }
        return List.of();
    }
}
