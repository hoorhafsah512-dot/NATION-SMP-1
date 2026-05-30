package com.nationssmp;

import com.nationssmp.commands.CommandRegistrar;
import com.nationssmp.listeners.*;
import com.nationssmp.managers.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NationsSMP — Main plugin entry point.
 *
 * Initialisation order:
 *  DataManager → NationManager → BotManager → AnimalCompanionManager
 *  → OathManager → LandManager → MartyrManager → TrophyManager
 *  → LegendaryItemManager → GlitchManager → DragonManager
 *  → Listeners → Commands
 */
public class NationsSMP extends JavaPlugin {

    // ── Managers ──────────────────────────────────────────────────────────────
    private DataManager            dataManager;
    private NationManager          nationManager;
    private BotManager             botManager;
    private AnimalCompanionManager animalCompanionManager;
    private OathManager            oathManager;
    private LandManager            landManager;
    private MartyrManager          martyrManager;
    private TrophyManager          trophyManager;
    private LegendaryItemManager   legendaryItemManager;
    private GlitchManager          glitchManager;
    private DragonManager          dragonManager;

    // ── Listeners (some need to be referenced by managers) ────────────────────
    private PlayerDeathListener deathListener;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        // Config
        saveDefaultConfig();

        // Managers
        dataManager            = new DataManager(this);
        nationManager          = new NationManager(this, dataManager);
        botManager             = new BotManager(this, nationManager);
        animalCompanionManager = new AnimalCompanionManager(this, nationManager);
        oathManager            = new OathManager(this, nationManager, botManager, dataManager);
        landManager            = new LandManager(this, nationManager, dataManager);
        martyrManager          = new MartyrManager(this, nationManager);
        trophyManager          = new TrophyManager(this, nationManager);
        legendaryItemManager   = new LegendaryItemManager(this, nationManager);
        glitchManager          = new GlitchManager(this, nationManager, legendaryItemManager);
        dragonManager          = new DragonManager(this, nationManager);

        // Listeners
        deathListener = new PlayerDeathListener(this);
        var pm = getServer().getPluginManager();
        pm.registerEvents(deathListener,                 this);
        pm.registerEvents(new PlayerJoinListener(this),  this);
        pm.registerEvents(new PlayerChatListener(this),  this);
        pm.registerEvents(new InventoryClickListener(this), this);
        pm.registerEvents(new BlockListener(this),       this);
        pm.registerEvents(new WorldChangeListener(this), this);

        // Commands
        CommandRegistrar.registerAll(this);

        getLogger().info("NationsSMP enabled — the nations rise!");
    }

    @Override
    public void onDisable() {
        // Save all data
        if (nationManager != null) nationManager.saveAll();

        // Graceful shutdown of scheduled tasks
        if (botManager != null)    botManager.shutdown();
        if (glitchManager != null) glitchManager.shutdown();

        getLogger().info("NationsSMP disabled — the nations rest.");
    }

    // ── Getters (used by listeners and commands) ──────────────────────────────

    public DataManager            getDataManager()            { return dataManager; }
    public NationManager          getNationManager()          { return nationManager; }
    public BotManager             getBotManager()             { return botManager; }
    public AnimalCompanionManager getAnimalCompanionManager() { return animalCompanionManager; }
    public OathManager            getOathManager()            { return oathManager; }
    public LandManager            getLandManager()            { return landManager; }
    public MartyrManager          getMartyrManager()          { return martyrManager; }
    public TrophyManager          getTrophyManager()          { return trophyManager; }
    public LegendaryItemManager   getLegendaryItemManager()   { return legendaryItemManager; }
    public GlitchManager          getGlitchManager()          { return glitchManager; }
    public DragonManager          getDragonManager()          { return dragonManager; }
    public PlayerDeathListener    getDeathListener()          { return deathListener; }
}
