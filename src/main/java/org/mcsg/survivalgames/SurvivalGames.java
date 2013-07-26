package org.mcsg.survivalgames;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.mcsg.survivalgames.events.*;
import org.mcsg.survivalgames.hooks.HookManager;
import org.mcsg.survivalgames.logging.LoggingManager;
import org.mcsg.survivalgames.logging.QueueManager;
import org.mcsg.survivalgames.stats.StatsManager;
import org.mcsg.survivalgames.util.ChestRatioStorage;
import org.mcsg.survivalgames.util.DatabaseManager;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;

public class SurvivalGames extends JavaPlugin {
	private static File datafolder;
	private static boolean disabling = false;
	public static boolean dbcon = false;
	public static boolean config_todate = false;
	public static int config_version = 3;
	private long start;

	public static List < String > auth = Arrays.asList(new String[] {
			"Double0negative", "iMalo", "Medic0987", "alex_markey", "skitscape", "AntVenom", "YoshiGenius", "pimpinpsp", "WinryR", "Jazed2011",
			"KiwiPantz", "blackracoon", "CuppingCakes", "4rr0ws", "Fawdz", "Timothy13", "rich91", "ModernPrestige", "Snowpool", "egoshk", 
			"nickm140",  "chaseoes", "Oceangrass", "GrailMore", "iAngelic", "Lexonia", "ChaskyT", "Anon232", "KHobbits", "dmulloy2"
	});

	private static SurvivalGames p;

	@Override
	public void onEnable() {
		p = this;

		// Ensure that all worlds are loaded. Fixes some issues with Multiverse loading after this plugin had started
		new Startup().runTaskLater(this, 10L);
		
		try {
			new Metrics(this).start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void onDisable() {
		disabling = false;
		start = System.currentTimeMillis();

		SettingsManager.getInstance().saveSpawns();
		SettingsManager.getInstance().saveSystemConfig();
		for (Game g : GameManager.getInstance().getGames()) {
			try {
				g.disable();
			} catch(Exception e) {
				//will throw useless "tried to register task blah blah error." Use the method below to reset the arena without a task.
			}
			
			QueueManager.getInstance().rollback(g.getID(), true);
		}

		$(getDescription().getFullName() + " has been disabled (" + (System.currentTimeMillis() - start) + "ms)");
	}

	public class Startup extends BukkitRunnable {
		
		@Override
		public void run() {
			datafolder = p.getDataFolder();
			start = System.currentTimeMillis();

			PluginManager pm = getServer().getPluginManager();
			setCommands();

			SettingsManager.getInstance().setup(p);
			MessageManager.getInstance().setup();
			GameManager.getInstance().setup(p);

			// Try loading everything that uses SQL
			try {
				FileConfiguration c = SettingsManager.getInstance().getConfig();
				if (c.getBoolean("stats.enabled")) DatabaseManager.getInstance().setup(p);
				QueueManager.getInstance().setup();
				StatsManager.getInstance().setup(p, c.getBoolean("stats.enabled"));
				dbcon = true;
			} catch (Exception e) {
				dbcon = false;
				e.printStackTrace();
				$(Level.SEVERE, "!!!Failed to connect to the database. Please check the settings and try again!!!");
				return;
			} finally {
				LobbyManager.getInstance().setup(p);
			}

			ChestRatioStorage.getInstance().setup();
			HookManager.getInstance().setup();
			pm.registerEvents(new PlaceEvent(), p);
			pm.registerEvents(new BreakEvent(), p);
			pm.registerEvents(new DeathEvent(), p);
			pm.registerEvents(new MoveEvent(), p);
			pm.registerEvents(new CommandCatch(), p);
			pm.registerEvents(new SignClickEvent(), p);
			pm.registerEvents(new ChestReplaceEvent(), p);
			pm.registerEvents(new LogoutEvent(), p);
			pm.registerEvents(new JoinEvent(p), p);
			pm.registerEvents(new TeleportEvent(), p);
			pm.registerEvents(LoggingManager.getInstance(), p);
			pm.registerEvents(new SpectatorEvents(), p);
			pm.registerEvents(new BandageUse(), p);
			pm.registerEvents(new KitEvents(), p);

			for (Player pl : p.getServer().getOnlinePlayers()) {
				if (GameManager.getInstance().getBlockGameId(pl.getLocation()) != -1) {
					pl.teleport(SettingsManager.getInstance().getLobbySpawn());
				}
			}

			$(getDescription().getFullName() + " has been enabled (" + (System.currentTimeMillis() - start) + "ms)");
		}
	}

	public void setCommands() {
		getCommand("survivalgames").setExecutor(new CommandHandler());
	}

	public static File getPluginDataFolder() {
		return datafolder;
	}

	public static boolean isDisabling() {
		return disabling;
	}

	public WorldEditPlugin getWorldEdit() {
		PluginManager pm = getServer().getPluginManager();
		if (pm.isPluginEnabled("WorldEdit")) {
			Plugin worldEdit = pm.getPlugin("WorldEdit");
			if (worldEdit instanceof WorldEditPlugin) {
				return (WorldEditPlugin)worldEdit;
			}
		}
		
		return null;
	}

	public static void $(String msg) {
		p.getLogger().log(Level.INFO, msg);
	}

	public static void $(Level l, String msg){
		p.getLogger().log(l, msg);
	}

	public static void debug(String msg) {
		if (SettingsManager.getInstance().getConfig().getBoolean("debug", false)) {
			$("[Debug] " + msg);
		}
	}

	public static void debug(int a) {
		debug(String.valueOf(a));
	}
	
	public static SurvivalGames getInstance() {
		return p;
	}
}