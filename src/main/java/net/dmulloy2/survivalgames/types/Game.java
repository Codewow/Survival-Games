package net.dmulloy2.survivalgames.types;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import net.dmulloy2.survivalgames.SurvivalGames;
import net.dmulloy2.survivalgames.api.PlayerGameDeathEvent;
import net.dmulloy2.survivalgames.api.PlayerJoinArenaEvent;
import net.dmulloy2.survivalgames.api.PlayerWinEvent;
import net.dmulloy2.survivalgames.managers.GameManager;
import net.dmulloy2.survivalgames.managers.MessageManager;
import net.dmulloy2.survivalgames.managers.MessageManager.PrefixType;
import net.dmulloy2.survivalgames.stats.StatsManager;
import net.dmulloy2.survivalgames.util.ItemReader;
import net.dmulloy2.survivalgames.util.Kit;
import net.dmulloy2.survivalgames.util.LocationUtil;
import net.dmulloy2.survivalgames.util.NameUtil;
import net.dmulloy2.survivalgames.util.SpectatorUtil;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Data container for a game
 */
public class Game
{
	public static enum GameMode
	{
		DISABLED, LOADING, INACTIVE, WAITING, STARTING, INGAME, FINISHING, RESETTING, ERROR
	}

	private GameMode mode = GameMode.DISABLED;
	private List<Player> activePlayers = new ArrayList<Player>();
	private List<Player> inactivePlayers = new ArrayList<Player>();
	private List<String> spectators = new ArrayList<String>();
	private List<Player> queue = new ArrayList<Player>();
	private HashMap<String, Object> flags = new HashMap<String, Object>();
	private HashMap<Player, Integer> nextspec = new HashMap<Player, Integer>();
	private List<Integer> tasks = new ArrayList<Integer>();

	private Arena arena;
	private int gameID;
	private int gcount = 0;
	private FileConfiguration config;
	private FileConfiguration system;
	private HashMap<Integer, Player> spawns = new HashMap<Integer, Player>();
	private HashMap<Player, ItemStack[][]> inv_store = new HashMap<Player, ItemStack[][]>();
	private int spawnCount = 0;
	private int vote = 0;
	private boolean disabled = false;
	private int endgameTaskID = 0;
	private boolean endgameRunning = false;
	private double rbpercent = 0;
	private String rbstatus = "";
	private long startTime = 0;
	private boolean countdownRunning;

	private HashMap<String, String> hookvars = new HashMap<String, String>();

	private MessageManager msgmgr;
	private GameManager gm;
	private StatsManager sm;

	private final SurvivalGames plugin;

	public Game(GameManager manager, int gameID)
	{
		this.gm = manager;

		this.plugin = gm.getPlugin();
		this.sm = plugin.getStatsManager();

		this.gameID = gameID;
		reloadConfig();
		setup();
	}

	public void reloadConfig()
	{
		config = plugin.getSettingsManager().getConfig();
		system = plugin.getSettingsManager().getSystemConfig();
	}

	public void $(String msg)
	{
		plugin.$(msg);
	}

	public void debug(String msg)
	{
		plugin.debug(msg);
	}

	// -------------------------//
	// Setup
	// -------------------------//
	public void setup()
	{
		mode = GameMode.LOADING;
		int x = system.getInt("sg-system.arenas." + gameID + ".x1");
		int y = system.getInt("sg-system.arenas." + gameID + ".y1");
		int z = system.getInt("sg-system.arenas." + gameID + ".z1");
		int x1 = system.getInt("sg-system.arenas." + gameID + ".x2");
		int y1 = system.getInt("sg-system.arenas." + gameID + ".y2");
		int z1 = system.getInt("sg-system.arenas." + gameID + ".z2");

		Location max = new Location(plugin.getSettingsManager().getGameWorld(gameID), Math.max(x, x1), Math.max(y, y1), Math.max(z, z1));
		debug("[Max] " + LocationUtil.locToString(max));
		Location min = new Location(plugin.getSettingsManager().getGameWorld(gameID), Math.min(x, x1), Math.min(y, y1), Math.min(z, z1));
		debug("[Min] " + LocationUtil.locToString(min));

		arena = new Arena(min, max);

		loadspawns();

		hookvars.put("arena", gameID + "");
		hookvars.put("maxplayers", spawnCount + "");
		hookvars.put("activeplayers", "0");

		mode = GameMode.WAITING;

		plugin.getLobbyManager().updateWall(gameID);
	}

	public void reloadFlags()
	{
		flags = plugin.getSettingsManager().getGameFlags(gameID);
	}

	public void saveFlags()
	{
		plugin.getSettingsManager().saveGameFlags(flags, gameID);
	}

	public void loadspawns()
	{
		for (int a = 1; a <= plugin.getSettingsManager().getSpawnCount(gameID); a++)
		{
			spawns.put(a, null);
			spawnCount = a;
		}
	}

	public void addSpawn()
	{
		spawnCount++;
		spawns.put(spawnCount, null);
	}

	public void setMode(GameMode m)
	{
		mode = m;
	}

	public GameMode getGameMode()
	{
		return mode;
	}

	public Arena getArena()
	{
		return arena;
	}

	public StatsManager getStatsManager()
	{
		return sm;
	}

	// -------------------------//
	// Enable
	// -------------------------//
	public void enable()
	{
		mode = GameMode.WAITING;
		if (disabled)
		{
			msgmgr.broadcastFMessage(PrefixType.INFO, "broadcast.gameenabled", "arena-" + gameID);
		}
		disabled = false;
		int b = (plugin.getSettingsManager().getSpawnCount(gameID) > queue.size()) ? queue.size() : plugin.getSettingsManager()
				.getSpawnCount(gameID);
		for (int a = 0; a < b; a++)
		{
			addPlayer(queue.remove(0));
		}
		int c = 1;
		for (Player p : queue)
		{
			msgmgr.sendMessage(PrefixType.INFO, "You are now #" + c + " in line for arena " + gameID, p);
			c++;
		}

		plugin.getLobbyManager().updateWall(gameID);

		msgmgr.broadcastFMessage(PrefixType.INFO, "broadcast.gamewaiting", "arena-" + gameID);

	}

	// -------------------------//
	// Add Player
	// -------------------------//
	public boolean addPlayer(Player p)
	{
		if (plugin.getSettingsManager().getLobbySpawn() == null)
		{
			msgmgr.sendFMessage(PrefixType.WARNING, "error.nolobbyspawn", p);
			return false;
		}

		if (!canJoinArena(p, gameID))
		{
			debug("permission needed to join arena: " + "sg.arena.join." + gameID);
			msgmgr.sendFMessage(PrefixType.WARNING, "game.nopermission", p, "arena-" + gameID);
			return false;
		}

		plugin.getHookManager().runHook("GAME_PRE_ADDPLAYER", "arena-" + gameID, "player-" + p.getName(), "maxplayers-" + spawns.size(),
				"players-" + activePlayers.size());

		gm.removeFromOtherQueues(p, gameID);

		if (gm.getPlayerGameId(p) != -1)
		{
			if (gm.isPlayerActive(p))
			{
				msgmgr.sendMessage(PrefixType.ERROR, "Cannot join multiple games!", p);
				return false;
			}
		}

		if (p.isInsideVehicle())
			p.leaveVehicle();

		if (spectators.contains(p))
			removeSpectator(p);

		if (mode == GameMode.WAITING || mode == GameMode.STARTING)
		{
			if (activePlayers.size() < plugin.getSettingsManager().getSpawnCount(gameID))
			{
				msgmgr.sendMessage(PrefixType.INFO, "Joining Arena " + gameID, p);
				PlayerJoinArenaEvent joinarena = new PlayerJoinArenaEvent(p, gm.getGame(gameID));
				plugin.getServer().getPluginManager().callEvent(joinarena);
				boolean placed = false;
				int spawnCount = plugin.getSettingsManager().getSpawnCount(gameID);

				for (int a = 1; a <= spawnCount; a++)
				{
					if (spawns.get(a) == null)
					{
						placed = true;
						spawns.put(a, p);
						p.setGameMode(org.bukkit.GameMode.SURVIVAL);

						p.teleport(plugin.getSettingsManager().getLobbySpawn());
						saveInv(p);
						clearInv(p);
						p.teleport(plugin.getSettingsManager().getSpawnPoint(gameID, a));

						p.setHealth(p.getMaxHealth());
						p.setFoodLevel(20);
						clearInv(p);

						activePlayers.add(p);
						sm.addPlayer(p, gameID);

						hookvars.put("activeplayers", activePlayers.size() + "");
						plugin.getLobbyManager().updateWall(gameID);
						showMenu(p);
						plugin.getHookManager().runHook("GAME_POST_ADDPLAYER", "activePlayers-" + activePlayers.size());

						if (spawnCount == activePlayers.size())
						{
							countdown(5);
						}

						break;
					}
				}

				if (!placed)
				{
					msgmgr.sendFMessage(PrefixType.ERROR, "error.gamefull", p, "arena-" + gameID);
					return false;
				}

			}
			else if (plugin.getSettingsManager().getSpawnCount(gameID) == 0)
			{
				msgmgr.sendMessage(PrefixType.WARNING, "No spawns set for Arena " + gameID + "!", p);
				return false;
			}
			else
			{
				msgmgr.sendFMessage(PrefixType.WARNING, "error.gamefull", p, "arena-" + gameID);
				return false;
			}

			msgFall(PrefixType.INFO, "game.playerjoingame", "player-" + p.getName(), "activeplayers-" + getActivePlayers(), "maxplayers-"
					+ plugin.getSettingsManager().getSpawnCount(gameID));
			if (activePlayers.size() >= config.getInt("auto-start-players") && !countdownRunning)
				countdown(config.getInt("auto-start-time"));

			return true;
		}
		else
		{
			if (config.getBoolean("enable-player-queue"))
			{
				if (!queue.contains(p))
				{
					queue.add(p);
					msgmgr.sendFMessage(PrefixType.INFO, "game.playerjoinqueue", p, "queuesize-" + queue.size());
				}
				int a = 1;
				for (Player qp : queue)
				{
					if (qp == p)
					{
						msgmgr.sendFMessage(PrefixType.INFO, "game.playercheckqueue", p, "queuepos-" + a);
						break;
					}
					a++;
				}
			}
		}

		if (mode == GameMode.INGAME)
		{
			msgmgr.sendFMessage(PrefixType.WARNING, "error.alreadyingame", p);
		}
		else if (mode == GameMode.DISABLED)
		{
			msgmgr.sendFMessage(PrefixType.WARNING, "error.gamedisabled", p, "arena-" + gameID);
		}
		else if (mode == GameMode.RESETTING)
		{
			msgmgr.sendFMessage(PrefixType.WARNING, "error.gameresetting", p);
		}
		else
		{
			msgmgr.sendMessage(PrefixType.INFO, "Cannot join game!", p);
		}

		plugin.getLobbyManager().updateWall(gameID);
		return false;
	}

	public boolean canJoinArena(Player p, int gameId)
	{
		return (p.hasPermission("sg.arenas.join." + gameId) || p.hasPermission("sg.arenas.join.*"));
	}

	// -------------------------//
	// Kit Menu
	// -------------------------//
	public void showMenu(Player p)
	{
		gm.openKitMenu(p);
		Inventory i = plugin.getServer().createInventory(p, 90, ChatColor.RED + "" + ChatColor.BOLD + "Kit Selection");

		int a = 0;
		int b = 0;

		List<Kit> kits = gm.getKits(p);
		plugin.debug(kits + "");
		if (kits == null || kits.size() == 0 || !plugin.getSettingsManager().getKits().getBoolean("enabled"))
		{
			gm.leaveKitMenu(p);
			return;
		}

		for (Kit k : kits)
		{
			ItemStack i1 = k.getIcon();
			ItemMeta im = i1.getItemMeta();

			debug(k.getName() + " " + i1 + " " + im);

			im.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + k.getName());
			i1.setItemMeta(im);
			i.setItem((9 * a) + b, i1);
			a = 2;

			for (ItemStack s2 : k.getContents())
			{
				if (s2 != null)
				{
					i.setItem((9 * a) + b, s2);
					a++;
				}
			}

			a = 0;
			b++;
		}

		p.openInventory(i);
		debug("Showing menu");
	}

	// -------------------------//
	// Remove from Queue
	// -------------------------//
	public void removeFromQueue(Player p)
	{
		queue.remove(p);
	}

	// --------------------------//
	// Vote
	// -------------------------//
	ArrayList<Player> voted = new ArrayList<Player>();

	public void vote(Player pl)
	{
		if (GameMode.STARTING == mode)
		{
			msgmgr.sendMessage(PrefixType.WARNING, "Game already starting!", pl);
			return;
		}
		if (GameMode.WAITING != mode)
		{
			msgmgr.sendMessage(PrefixType.WARNING, "Game already started!", pl);
			return;
		}
		if (voted.contains(pl))
		{
			msgmgr.sendMessage(PrefixType.WARNING, "You already voted!", pl);
			return;
		}

		vote++;
		voted.add(pl);

		for (Player player : activePlayers)
		{
			msgmgr.sendFMessage(PrefixType.INFO, "game.playervote", player, "player-" + pl.getName(), "voted-" + vote, "players-"
					+ getActivePlayers());
		}

		plugin.getHookManager().runHook("PLAYER_VOTE", "player-" + pl.getName());

		if ((((vote + 0.0) / (getActivePlayers() + 0.0)) >= (config.getInt("auto-start-vote") + 0.0) / 100) && getActivePlayers() > 1)
		{
			countdown(config.getInt("auto-start-time"));
		}
	}

	// --------------------------//
	// Start the game
	// -------------------------//
	public void startGame()
	{
		if (mode == GameMode.INGAME)
		{
			return;
		}

		if (activePlayers.size() <= 1)
		{
			for (Player pl : activePlayers)
			{
				msgmgr.sendMessage(PrefixType.WARNING, "Not enough players!", pl);
				mode = GameMode.WAITING;
				plugin.getLobbyManager().updateWall(gameID);

			}
			return;
		}
		else
		{
			startTime = new Date().getTime();
			for (Player pl : activePlayers)
			{
				pl.setHealth(pl.getMaxHealth());
				// clearInv(pl);
				msgmgr.sendFMessage(PrefixType.INFO, "game.goodluck", pl);
			}
			if (config.getBoolean("restock-chest"))
			{
				plugin.getSettingsManager().getGameWorld(gameID).setTime(0);
				gcount++;
				tasks.add(plugin.getServer().getScheduler().scheduleSyncDelayedTask(gm.getPlugin(), new NightChecker(), 14400));
			}
			if (config.getInt("grace-period") != 0)
			{
				for (Player play : activePlayers)
				{
					msgmgr.sendMessage(PrefixType.INFO, "You have a " + config.getInt("grace-period") + " second grace period!", play);
				}
				plugin.getServer().getScheduler().scheduleSyncDelayedTask(gm.getPlugin(), new BukkitRunnable()
				{
					@Override
					public void run()
					{
						for (Player play : activePlayers)
						{
							msgmgr.sendMessage(PrefixType.INFO, "Grace period has ended!", play);
						}
					}
				}, config.getInt("grace-period") * 20);
			}
			if (config.getBoolean("deathmatch.enabled"))
			{
				tasks.add(plugin.getServer().getScheduler()
						.scheduleSyncDelayedTask(gm.getPlugin(), new DeathMatch(), config.getInt("deathmatch.time") * 20 * 60));
			}
		}

		mode = GameMode.INGAME;
		plugin.getLobbyManager().updateWall(gameID);
		msgmgr.broadcastFMessage(PrefixType.INFO, "broadcast.gamestarted", "arena-" + gameID);

	}

	// -------------------------//
	// Countdowns
	// -------------------------//
	public int getCountdownTime()
	{
		return count;
	}

	int count = 20;
	int tid = 0;

	public void countdown(int time)
	{
		// plugin.getServer().broadcastMessage(""+time);
		msgmgr.broadcastFMessage(PrefixType.INFO, "broadcast.gamestarting", "arena-" + gameID, "t-" + time);
		countdownRunning = true;
		count = time;
		plugin.getServer().getScheduler().cancelTask(tid);

		if (mode == GameMode.WAITING || mode == GameMode.STARTING)
		{
			mode = GameMode.STARTING;
			tid = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(gm.getPlugin(), new Runnable()
			{
				@Override
				public void run()
				{
					if (count > 0)
					{
						if (count % 10 == 0)
						{
							msgFall(PrefixType.INFO, "game.countdown", "t-" + count);
						}
						if (count < 6)
						{
							msgFall(PrefixType.INFO, "game.countdown", "t-" + count);

						}
						count--;
						plugin.getLobbyManager().updateWall(gameID);
					}
					else
					{
						startGame();
						plugin.getServer().getScheduler().cancelTask(tid);
						countdownRunning = false;
					}
				}
			}, 0, 20);

		}
	}

	// -------------------------//
	// Remove a player
	// -------------------------//
	public void removePlayer(Player p, boolean left)
	{
		p.teleport(plugin.getSettingsManager().getLobbySpawn());
		if (mode == GameMode.INGAME)
		{
			killPlayer(p, left);
		}
		else
		{
			sm.removePlayer(p, gameID);
			restoreInv(p);
			activePlayers.remove(p);
			inactivePlayers.remove(p);

			for (Object in : spawns.keySet().toArray())
			{
				if (spawns.get(in) == p)
					spawns.remove(in);
			}
			plugin.getLobbyManager().clearSigns(gameID);

			msgFall(PrefixType.INFO, "game.playerleavegame", "player-" + p.getName());
		}

		plugin.getHookManager().runHook("PLAYER_REMOVED", "player-" + p.getName());

		plugin.getLobbyManager().updateWall(gameID);
	}

	public void playerLeave(Player p)
	{

	}

	// -------------------------//
	// Kill a player
	// -------------------------//
	public void killPlayer(Player p, boolean left)
	{
		clearInv(p);
		if (!left)
		{
			p.teleport(plugin.getSettingsManager().getLobbySpawn());
		}
		sm.playerDied(p, activePlayers.size(), gameID, new Date().getTime() - startTime);

		if (!activePlayers.contains(p))
		{
			return;
		}
		else
		{
			restoreInv(p);
		}

		activePlayers.remove(p);
		inactivePlayers.add(p);
		if (left)
		{
			PlayerGameDeathEvent leavearena = new PlayerGameDeathEvent(p, p, this);
			plugin.getServer().getPluginManager().callEvent(leavearena);
			msgFall(PrefixType.INFO, "game.playerleavegame", "player-" + p.getName());
		}
		else
		{
			if (mode != GameMode.WAITING && p.getLastDamageCause() != null && p.getLastDamageCause().getCause() != null)
			{
				switch (p.getLastDamageCause().getCause())
				{
					case ENTITY_ATTACK:
						if (p.getLastDamageCause().getEntityType() == EntityType.PLAYER)
						{
							Player killer = p.getKiller();
							PlayerGameDeathEvent leavearena = new PlayerGameDeathEvent(p, killer, this);
							plugin.getServer().getPluginManager().callEvent(leavearena);
							msgFall(PrefixType.INFO,
									"death." + p.getLastDamageCause().getEntityType(),
									"player-" + (NameUtil.auth.contains(p.getName()) ? ChatColor.DARK_RED + "" + ChatColor.BOLD : "")
											+ p.getName(), "killer-"
											+ ((killer != null) ? (NameUtil.auth.contains(killer.getName()) ? ChatColor.DARK_RED + ""
													+ ChatColor.BOLD : "")
													+ killer.getName() : "Unknown"),
									"item-"
											+ ((killer != null) ? ItemReader.getFriendlyName(killer.getItemInHand().getType())
													: "Unknown Item"));
							if (killer != null && p != null)
								sm.addKill(killer, p, gameID);
						}
						else
						{
							msgFall(PrefixType.INFO,
									"death." + p.getLastDamageCause().getEntityType(),
									"player-" + (NameUtil.auth.contains(p.getName()) ? ChatColor.DARK_RED + "" + ChatColor.BOLD : "")
											+ p.getName(), "killer-" + p.getLastDamageCause().getEntityType());
						}
						break;
					case FIRE:
					case FIRE_TICK:
					case MAGIC:
					case THORNS:
					case PROJECTILE:
						if (p.getKiller() != null)
						{
							Player killer = p.getKiller();
							PlayerGameDeathEvent leavearena = new PlayerGameDeathEvent(p, killer, this);
							plugin.getServer().getPluginManager().callEvent(leavearena);
							msgFall(PrefixType.INFO,
									"death." + p.getLastDamageCause().getCause(),
									"player-" + (NameUtil.auth.contains(p.getName()) ? ChatColor.DARK_RED + "" + ChatColor.BOLD : "")
											+ p.getName(), "killer-" + p.getKiller().getName());
							if (killer != null && p != null)
								sm.addKill(killer, p, gameID);
						}
						else
						{
							msgFall(PrefixType.INFO,
									"death." + p.getLastDamageCause().getCause(),
									"player-" + (NameUtil.auth.contains(p.getName()) ? ChatColor.DARK_RED + "" + ChatColor.BOLD : "")
											+ p.getName(), "killer-" + p.getLastDamageCause().getCause());
						}
						break;
					default:
						msgFall(PrefixType.INFO, "death." + p.getLastDamageCause().getCause(), "player-"
								+ (NameUtil.auth.contains(p.getName()) ? ChatColor.DARK_RED + "" + ChatColor.BOLD : "") + p.getName(),
								"killer-" + p.getLastDamageCause().getCause());
						break;
				}
				if (getActivePlayers() > 1)
				{
					for (Player pl : getAllPlayers())
					{
						msgmgr.sendMessage(PrefixType.INFO, ChatColor.DARK_AQUA + "There are " + ChatColor.YELLOW + "" + getActivePlayers()
								+ ChatColor.DARK_AQUA + " players remaining!", pl);
					}
				}
			}
		}

		for (Player pe : activePlayers)
		{
			Location l = pe.getLocation();
			l.setY(l.getWorld().getMaxHeight());
			l.getWorld().strikeLightningEffect(l);
		}

		if (getActivePlayers() <= config.getInt("endgame.players") && config.getBoolean("endgame.fire-lighting.enabled") && !endgameRunning)
		{
			tasks.add(plugin.getServer().getScheduler()
					.scheduleSyncRepeatingTask(gm.getPlugin(), new EndgameManager(), 0, config.getInt("endgame.fire-lighting.interval") * 20));
		}

		if (activePlayers.size() < 2 && mode != GameMode.WAITING)
		{
			playerWin(p);
			endGame();
		}

		plugin.getLobbyManager().updateWall(gameID);
	}

	// -------------------------//
	// Player win
	// -------------------------//
	public void playerWin(Player p)
	{
		if (GameMode.DISABLED == mode)
			return;
		Player win = activePlayers.get(0);
		// clearInv(p);
		win.teleport(plugin.getSettingsManager().getLobbySpawn());
		restoreInv(win);
		msgmgr.broadcastFMessage(PrefixType.INFO, "game.playerwin", "arena-" + gameID, "victim-" + p.getName(), "player-" + win.getName());
		plugin.getLobbyManager().display(new String[] { win.getName(), "", "Won the ", "Survival Games!" }, gameID);

		mode = GameMode.FINISHING;
		if (config.getBoolean("reward.enabled", false))
		{
			List<String> items = config.getStringList("reward.contents");
			for (int i = 0; i <= (items.size() - 1); i++)
			{
				ItemStack item = ItemReader.read(items.get(i));
				win.getInventory().addItem(item);
			}
		}

		clearSpecs();
		win.setHealth(p.getMaxHealth());
		win.setFoodLevel(20);
		win.setFireTicks(0);
		win.setFallDistance(0);

		PlayerWinEvent winEvent = new PlayerWinEvent(win, p, this);
		plugin.getServer().getPluginManager().callEvent(winEvent);

		sm.playerWin(win, gameID, new Date().getTime() - startTime);
		sm.saveGame(gameID, win, getActivePlayers() + getInactivePlayers(), new Date().getTime() - startTime);

		activePlayers.clear();
		inactivePlayers.clear();
		spawns.clear();

		loadspawns();
		plugin.getLobbyManager().updateWall(gameID);
		msgmgr.broadcastFMessage(PrefixType.INFO, "broadcast.gameend", "arena-" + gameID);

	}

	// -------------------------//
	// End Game
	// -------------------------//
	public void endGame()
	{
		mode = GameMode.WAITING;
		
		if (! plugin.isDisabling())
			resetArena();
		
		plugin.getLobbyManager().clearSigns(gameID);
		plugin.getLobbyManager().updateWall(gameID);

	}

	// -------------------------//
	// Disable
	// -------------------------//
	public void disable()
	{
		disabled = true;
		spawns.clear();

		for (int a = 0; a < activePlayers.size(); a = 0)
		{
			try
			{
				Player p = activePlayers.get(a);
				msgmgr.sendMessage(PrefixType.WARNING, "Game disabled!", p);
				removePlayer(p, false);
			}
			catch (Exception e)
			{
				//
			}
		}

		for (int a = 0; a < inactivePlayers.size(); a = 0)
		{
			try
			{
				Player p = inactivePlayers.remove(a);
				msgmgr.sendMessage(PrefixType.WARNING, "Game disabled!", p);
			}
			catch (Exception e)
			{
				//
			}

		}

		clearSpecs();
		queue.clear();

		endGame();
		plugin.getLobbyManager().updateWall(gameID);
		msgmgr.broadcastFMessage(PrefixType.INFO, "broadcast.gamedisabled", "arena-" + gameID);

	}

	// -------------------------//
	// Reset
	// -------------------------//
	public void resetArena()
	{
		for (Integer i : tasks)
		{
			plugin.getServer().getScheduler().cancelTask(i);
		}

		tasks.clear();
		vote = 0;
		voted.clear();

		mode = GameMode.RESETTING;
		endgameRunning = false;

		plugin.getServer().getScheduler().cancelTask(endgameTaskID);
		gm.gameEndCallBack(gameID);
		plugin.getQueueManager().rollback(gameID, false);
		plugin.getLobbyManager().updateWall(gameID);

	}

	public void resetCallback()
	{
		if (!disabled)
		{
			enable();
		}
		else
		{
			mode = GameMode.DISABLED;
		}

		plugin.getLobbyManager().updateWall(gameID);
	}

	// -------------------------//
	// Save a player's inventory
	// -------------------------//
	public void saveInv(Player p)
	{
		ItemStack[][] store = new ItemStack[2][1];

		store[0] = p.getInventory().getContents();
		store[1] = p.getInventory().getArmorContents();

		inv_store.put(p, store);

	}

	public void restoreInvOffline(String p)
	{
		restoreInv(plugin.getServer().getPlayer(p));
	}

	// -------------------------//
	// Spectating
	// -------------------------//
	public void addSpectator(Player p)
	{
		if (mode != GameMode.INGAME)
		{
			msgmgr.sendMessage(PrefixType.WARNING, "You can only spectate running games!", p);
			return;
		}

		saveInv(p);
		clearInv(p);
		p.teleport(plugin.getSettingsManager().getSpawnPoint(gameID, 1).add(0, 10, 0));

		plugin.getHookManager().runHook("PLAYER_SPECTATE", "player-" + p.getName());

		for (Player pl : plugin.getServer().getOnlinePlayers())
		{
			pl.hidePlayer(p);
		}

		p.setAllowFlight(true);
		p.setFlying(true);
		spectators.add(p.getName());

		addItem(p, new ItemStack(Material.COMPASS));

		SpectatorUtil.addSpectator(p, this);
	}

	public List<Player> getActivePlayerList()
	{
		return activePlayers;
	}

	@SuppressWarnings("deprecation")
	public void addItem(Player p, ItemStack stack)
	{
		p.getInventory().addItem(stack);
		p.updateInventory();
	}

	public void removeSpectator(Player p)
	{
		if (p.isOnline())
		{
			for (Player pl : plugin.getServer().getOnlinePlayers())
			{
				pl.showPlayer(p);
			}
		}

		clearInv(p);
		restoreInv(p);
		p.setAllowFlight(false);
		p.setFlying(false);
		p.setFallDistance(0);
		p.setHealth(p.getMaxHealth());
		p.setFoodLevel(20);
		p.setSaturation(20);
		p.teleport(plugin.getSettingsManager().getLobbySpawn());

		SpectatorUtil.removeSpectator(p);

		spectators.remove(p.getName());
	}

	public void clearSpecs()
	{
		for (int a = 0; a < spectators.size(); a = 0)
		{
			removeSpectator(plugin.getServer().getPlayerExact(spectators.get(0)));
		}

		spectators.clear();
		nextspec.clear();
	}

	public HashMap<Player, Integer> getNextSpec()
	{
		return nextspec;
	}

	// -------------------------//
	// Inventory Work
	// -------------------------//
	@SuppressWarnings("deprecation")
	public void restoreInv(Player p)
	{
		try
		{
			clearInv(p);
			p.getInventory().setContents(inv_store.get(p)[0]);
			p.getInventory().setArmorContents(inv_store.get(p)[1]);
			inv_store.remove(p);
			p.updateInventory();
		}
		catch (Exception e)
		{
			//
		}
	}

	@SuppressWarnings("deprecation")
	public void clearInv(Player p)
	{
		ItemStack[] inv = p.getInventory().getContents();
		for (int i = 0; i < inv.length; i++)
		{
			inv[i] = null;
		}
		p.getInventory().setContents(inv);
		inv = p.getInventory().getArmorContents();
		for (int i = 0; i < inv.length; i++)
		{
			inv[i] = null;
		}
		p.getInventory().setArmorContents(inv);
		p.updateInventory();
	}

	// -------------------------//
	// Check for night
	// -------------------------//
	class NightChecker extends BukkitRunnable
	{
		boolean reset = false;
		int tgc = gcount;

		@Override
		public void run()
		{
			if (plugin.getSettingsManager().getGameWorld(gameID).getTime() > 14000)
			{
				for (Player pl : activePlayers)
				{
					msgmgr.sendMessage(PrefixType.INFO, "Chests restocked!", pl);
				}
				gm.openedChest.get(gameID).clear();
				reset = true;
			}

		}
	}

	// -------------------------//
	// Ending Games
	// -------------------------//
	class EndgameManager extends BukkitRunnable
	{
		@Override
		public void run()
		{
			for (Player player : activePlayers.toArray(new Player[0]))
			{
				Location l = player.getLocation();
				l.add(0, 5, 0);
				player.getWorld().strikeLightningEffect(l);
			}

		}
	}

	// -------------------------//
	// Death Match
	// -------------------------//
	class DeathMatch extends BukkitRunnable
	{
		@Override
		public void run()
		{
			for (Player p : activePlayers)
			{
				for (int a = 0; a < spawns.size(); a++)
				{
					if (spawns.get(a) == p)
					{
						p.teleport(plugin.getSettingsManager().getSpawnPoint(gameID, a));
						break;
					}
				}
			}

			tasks.add(plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new BukkitRunnable()
			{
				@Override
				public void run()
				{
					for (Player p : activePlayers)
					{
						p.getLocation().getWorld().strikeLightning(p.getLocation());
					}
				}
			}, config.getInt("deathmatch.killtime") * 20 * 60));
		}
	}

	public boolean isBlockInArena(Location v)
	{
		return arena.containsBlock(v);
	}

	public boolean isProtectionOn()
	{
		long t = startTime / 1000;
		long l = config.getLong("grace-period");
		long d = new Date().getTime() / 1000;
		return ((d - t) < l);
	}

	public int getID()
	{
		return gameID;
	}

	public int getActivePlayers()
	{
		return activePlayers.size();
	}

	public int getInactivePlayers()
	{
		return inactivePlayers.size();
	}

	public Player[][] getPlayers()
	{
		return new Player[][] { activePlayers.toArray(new Player[0]), inactivePlayers.toArray(new Player[0]) };
	}

	public ArrayList<Player> getAllPlayers()
	{
		ArrayList<Player> all = new ArrayList<Player>();
		all.addAll(activePlayers);
		all.addAll(inactivePlayers);
		return all;
	}

	public boolean isSpectator(Player p)
	{
		return spectators.contains(p.getName());
	}

	public boolean isInQueue(Player p)
	{
		return queue.contains(p);
	}

	public boolean isPlayerActive(Player player)
	{
		return activePlayers.contains(player);
	}

	public boolean isPlayerinactive(Player player)
	{
		return inactivePlayers.contains(player);
	}

	public boolean hasPlayer(Player p)
	{
		return activePlayers.contains(p) || inactivePlayers.contains(p);
	}

	public GameMode getMode()
	{
		return mode;
	}

	public synchronized void setRBPercent(double d)
	{
		rbpercent = d;
	}

	public double getRBPercent()
	{
		return rbpercent;
	}

	public void setRBStatus(String s)
	{
		rbstatus = s;
	}

	public String getRBStatus()
	{
		return rbstatus;
	}

	public String getName()
	{
		return "Arena " + gameID;
	}

	public void msgFall(PrefixType type, String msg, String... vars)
	{
		for (Player p : getAllPlayers())
		{
			msgmgr.sendFMessage(type, msg, p, vars);
		}
	}

	/*
	 * public void randomTrap() {
	 * 
	 * World world = plugin.getSettingsManager().getGameWorld(gameID);
	 * 
	 * double xcord; double zcord; double ycord = 80; Random rand = new
	 * Random(); xcord = rand.nextInt(1000); zcord = rand.nextInt(1000);
	 * Location trap = new Location(world, xcord, ycord, zcord); boolean isAir =
	 * true;
	 * 
	 * while(isAir == true) { ycord--; Byte blockData =
	 * trap.getBlock().getData(); if(blockData != 0) {
	 * trap.getBlock().setType(Material.AIR); ycord--;
	 * trap.getBlock().setType(Material.AIR); ycord--;
	 * trap.getBlock().setType(Material.AIR); ycord--;
	 * trap.getBlock().setType(Material.LAVA); isAir = false; } else { isAir =
	 * true; } }
	 * 
	 * }
	 */
}