package net.dmulloy2.survivalgames.commands;

import net.dmulloy2.survivalgames.SurvivalGames;
import net.dmulloy2.survivalgames.managers.MessageManager;
import net.dmulloy2.survivalgames.managers.MessageManager.PrefixType;
import net.dmulloy2.survivalgames.types.Permission;

/**
 * @author dmulloy2
 */

public class CmdTeleport extends SurvivalGamesCommand
{
	public CmdTeleport(SurvivalGames plugin)
	{
		super(plugin);
		this.name = "teleport";
		this.requiredArgs.add("id");
		this.description = "Teleport to an arena's spawn";
		
		this.permission = Permission.STAFF_TELEPORT;
	}
	
	@Override
	public void perform()
	{
		try
		{
			int a = Integer.parseInt(args[0]);
			try
			{
				player.teleport(plugin.getSettingsManager().getSpawnPoint(a, 1));
			}
			catch (Exception e)
			{
				messageManager.sendMessage(MessageManager.PrefixType.ERROR, "error.nospawns", player);
			}
		}
		catch (NumberFormatException e)
		{
			messageManager.sendFMessage(PrefixType.ERROR, "error.notanumber", player, "input-" + args[0]);
		}
	}
}