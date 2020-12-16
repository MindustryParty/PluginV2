package party.mindustry.pluginv2;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public class MPPV2 extends Plugin {

	private Properties config;
	private HikariDataSource hikari;
	private ArrayList<Player> players = new ArrayList<Player>();
	private ArrayList<Player> rainbowPlayers = new ArrayList<Player>();
	private HashMap<Player, Integer> rainbowStatus = new HashMap<Player, Integer>();
	private HashMap<Player, String> playerRanks = new HashMap<Player, String>();
	private HashMap<Player, String> playerPrefixes = new HashMap<Player, String>();
	private HashMap<Player, String> originalName = new HashMap<Player, String>();
	
	@Override
	public void init() {
		File configPath = new File(Vars.dataDirectory.absolutePath() + "mppv2.properties");

		if (!configPath.exists()) {
			// Make new config.
			Log.info("No config found, a new one will be created.");
			try (OutputStream output = new FileOutputStream(configPath)) {
				Properties defaultProp = new Properties();

				defaultProp.setProperty("db.jdbcurl", "jdbc:mysql://localhost:3306/mppv2");
				defaultProp.setProperty("db.user", "root");
				defaultProp.setProperty("db.password", "password");
				defaultProp.setProperty("server.type", "vanilla");

				defaultProp.store(output, null);
			} catch (IOException io) {
				Log.info("Failed to create config file:");
				io.printStackTrace();
			}
		}

		Log.info("Loading config...");
		try (InputStream input = new FileInputStream(configPath)) {
			config = new Properties();
			config.load(input);
		} catch (IOException ex) {
			Log.info("Failed to load config file:");
			ex.printStackTrace();
		}

		HikariConfig hconfig = new HikariConfig();
		hconfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
		hconfig.setJdbcUrl(config.getProperty("db.jdbcurl"));
		hconfig.setUsername(config.getProperty("db.user"));
		hconfig.setPassword(config.getProperty("db.password"));
		hconfig.addDataSourceProperty("cachePrepStmts", "true");
		hconfig.addDataSourceProperty("prepStmtCacheSize", "250");
		hconfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

		hikari = new HikariDataSource(hconfig);

		Events.on(PlayerJoin.class, e -> {
			players.add(e.player);
			try {
				Connection mysqlConnection = hikari.getConnection();
				PreparedStatement rowCountStatement = mysqlConnection
						.prepareStatement("SELECT COUNT(*) AS rowcount FROM players WHERE uuid=?;");
				rowCountStatement.setString(1, e.player.uuid());

				ResultSet rowResult = rowCountStatement.executeQuery();
				rowResult.next();

				if (rowResult.getInt("rowcount") == 0) {
					// Player doesn't exist yet, add them to the database.
					PreparedStatement insertStatement = mysqlConnection
							.prepareStatement("INSERT INTO players (uuid) VALUES (?)");
					insertStatement.setString(1, e.player.uuid());
					insertStatement.execute();
				}

				// Update their last known name.
				PreparedStatement nameStatement = mysqlConnection
						.prepareStatement("UPDATE players SET name=? WHERE uuid=?");
				nameStatement.setString(1, e.player.name);
				nameStatement.setString(2, e.player.uuid());
				nameStatement.execute();

				// Fetch player info.
				PreparedStatement playerInfoStatement = mysqlConnection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, e.player.uuid());
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				String rank = playerInfo.getString("rank");

				playerRanks.put(e.player, rank);

				String rankP = "";

				e.player.admin(false);

				if (rank.equals("active")) {
					rankP = "[orange]ACTIVE PLAYER[] ";
				} else if (rank.equals("donator")) {
					rankP = "[accent]DONATOR[] ";
				} else if (rank.equals("moderator")) {
					rankP = "[green]MODERATOR[] ";
					e.player.admin(true);
				} else if (rank.equals("admin")) {
					rankP = "[red]ADMIN[] ";
					e.player.admin(true);
				} else {
					e.player.name = Strings.stripColors(e.player.name);
				}

				originalName.put(e.player, e.player.name);
				playerPrefixes.put(e.player, rankP);

				e.player.name = rankP + e.player.name;
				mysqlConnection.close();
			} catch (Exception ex) {
				e.player.con.kick("[red]A database error has occured. Please try again later.[]");
			}
			Call.infoToast("[green]+[] " + e.player.name, 5);
			Call.sendMessage(e.player.name + " [accent]joined.[]");
			Log.info(e.player.name + " joined. [" + e.player.uuid() + "]");
		});

		Events.on(PlayerLeave.class, e -> {
			Call.infoToast("[red]-[] " + e.player.name, 5);
			Call.sendMessage(e.player.name + " [accent]left.[]");
			Log.info(e.player.name + " left. [" + e.player.uuid() + "]");
			players.remove(e.player);
			originalName.remove(e.player);
			playerRanks.remove(e.player);
			playerPrefixes.remove(e.player);

			if (rainbowPlayers.contains(e.player)) {
				rainbowPlayers.remove(e.player);
				rainbowStatus.remove(e.player);
			}
		});

		Events.on(Trigger.update.getClass(), e -> {
			for (Player r : rainbowPlayers) {
				Integer hue = rainbowStatus.get(r);
				hue++;
				if (hue > 360) {
					hue = 0;
				}
				rainbowStatus.put(r, hue);
				String hexCode = Integer.toHexString(Color.getHSBColor(hue / 360f, 1f, 1f).getRGB()).substring(2);
				r.name = playerPrefixes.get(r) + "[#" + hexCode + "]" + Strings.stripColors(originalName.get(r));
			}
		});

		Timer timer = new Timer();

		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				for (Player p : players) {
					if (!p.con.isConnected()) {
						players.remove(p);
					} else {
						try {
							Connection mysqlConnection = hikari.getConnection();
							if (config.getProperty("server.type").equals("modded")) {
								PreparedStatement increasePlaytimeStatement = mysqlConnection.prepareStatement(
										"UPDATE players SET `playtime_modded` = `playtime_modded` + 1, `playtime_total` = `playtime_total` + 1 WHERE uuid=?;");
								increasePlaytimeStatement.setString(1, p.uuid());
								increasePlaytimeStatement.execute();
							} else {
								PreparedStatement increasePlaytimeStatement = mysqlConnection.prepareStatement(
										"UPDATE players SET `playtime_vanilla` = `playtime_vanilla` + 1, `playtime_total` = `playtime_total` + 1 WHERE uuid=?;");
								increasePlaytimeStatement.setString(1, p.uuid());
								increasePlaytimeStatement.execute();
							}
							mysqlConnection.close();
						} catch (SQLException e) {
							System.out.println("Something went wrong during the playtime increase:");
							e.printStackTrace();
						}
					}
				}
			}
		}, 60 * 1000, 60 * 1000);

		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				Call.sendMessage(
						"[#7289DA]Did you know that we have a discord server? Join it by going to: [purple]https://mindustry.party/discord[#7289DA].");
			}
		}, 15 * 60 * 1000, 15 * 60 * 1000);
	}

	// Register commands that run on the server.
	@Override
	public void registerServerCommands(CommandHandler handler) {
	}

	// Register commands that player can invoke in-game.
	@Override
	public void registerClientCommands(CommandHandler handler) {
		handler.<Player>register("playtimetop", "", "List the ten players that have the most play-time.",
				(args, player) -> {
					try {
						Connection mysqlConnection = hikari.getConnection();
						PreparedStatement playerTopStatement;
						String server = "";
						if (config.getProperty("server.type").equals("modded")) {
							server = "Modded";
							playerTopStatement = mysqlConnection
									.prepareStatement("SELECT * FROM players ORDER BY playtime_modded DESC LIMIT 10;");
						} else {
							server = "Vanilla";
							playerTopStatement = mysqlConnection
									.prepareStatement("SELECT * FROM players ORDER BY playtime_vanilla DESC LIMIT 10;");
						}
						ResultSet playerTop = playerTopStatement.executeQuery();
						String text = "Top 10 - Playtime (" + server + " Server)";
						int pos = 1;
						while (playerTop.next()) {
							int pt = (server == "Vanilla" ? playerTop.getInt("playtime_vanilla")
									: playerTop.getInt("playtime_modded"));
							text += "\n[accent]#" + pos + " -[white] " + playerTop.getString("name") + " [accent]- "
									+ pt + " minute" + (pt == 1 ? "" : "s");
							pos++;
						}
						Call.infoMessage(player.con, text);
						mysqlConnection.close();
					} catch (SQLException e) {
						// Something went wrong, inform them.
						Call.infoMessage(player.con, "[red]Something went wrong. Please try again later.");
					}
				});

		handler.<Player>register("rainbow", "", "[Donator+] Toggle the rainbow-name effect.", (args, player) -> {
			if (playerRanks.get(player).equals("default")) {
				Call.infoMessage(player.con, "[red]You need the [accent]DONATOR [red]rank or higher to do that.");
			} else {
				if (rainbowPlayers.contains(player)) {
					rainbowStatus.remove(player);
					rainbowPlayers.remove(player);
					player.sendMessage("[accent]Disabled rainbow-name effect.");
					player.name = playerPrefixes.get(player) + originalName.get(player);
				} else {
					rainbowStatus.put(player, 0);
					rainbowPlayers.add(player);
					player.sendMessage("[accent]Enabled rainbow-name effect.");
				}
			}
		});

		handler.<Player>register("stats", "", "Show stats about yourself.", (args, player) -> {
			try {
				Connection mysqlConnection = hikari.getConnection();
				PreparedStatement playerInfoStatement = mysqlConnection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid());
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				int pt_v = playerInfo.getInt("playtime_vanilla");
				int pt_m = playerInfo.getInt("playtime_modded");
				int pt_t = playerInfo.getInt("playtime_total");
				Call.infoMessage(player.con,
						"Your statistics:" + "\n" + "Playtime (Vanilla) : [accent]" + pt_v + " minute"
								+ (pt_v == 1 ? "" : "s") + "[]\n" + "Playtime (Modded) : [accent]" + pt_m + " minute"
								+ (pt_m == 1 ? "" : "s") + "[]\n" + "Playtime (Total) : [accent]" + pt_t + " minute"
								+ (pt_t == 1 ? "" : "s") + "[]");
				mysqlConnection.close();
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.infoMessage(player.con, "[red]Something went wrong. Please try again later.");
			}
		});

		handler.<Player>register("setrank", "<player> <rank>", "[Admin-only] Set a player rank.", (args, player) -> {
			try {
				if (!playerRanks.get(player).equals("admin")) {
					Call.infoMessage(player.con, "[red]You need to be [accent]ADMIN [red]to do that.");
				} else {
					Connection mysqlConnection = hikari.getConnection();
					String rankToSet = args[1].toLowerCase();
					String[] valid = { "default", "active", "donator", "moderator" };
					if (!Arrays.stream(valid).anyMatch(t -> t.equals(rankToSet))) {
						player.sendMessage("[red]Valid ranks: " + Strings.join(", ", valid) + ".");
						return;
					}

					boolean found = false;
					for (Player p : players) {
						if (Strings.stripColors(originalName.get(p)).equalsIgnoreCase(args[0])) {
							found = true;
						}
					}
					if (!found) {
						player.sendMessage("[red]That player is not online!");
					} else {
						for (Player p : players) {
							if (Strings.stripColors(originalName.get(p)).equalsIgnoreCase(args[0])) {
								PreparedStatement playerInfoStatement2 = mysqlConnection
										.prepareStatement("SELECT * FROM players WHERE uuid=?;");
								playerInfoStatement2.setString(1, p.uuid());
								ResultSet playerInfo2 = playerInfoStatement2.executeQuery();
								playerInfo2.next();
								String rankP = playerInfo2.getString("rank");
								if (rankP.equals("admin")) {
									player.sendMessage("[red]That player is an admin!");
									return;
								}
								PreparedStatement setRankStatement = mysqlConnection
										.prepareStatement("UPDATE players SET rank = '" + rankToSet + "' WHERE uuid=?");
								setRankStatement.setString(1, p.uuid());
								setRankStatement.execute();
								playerRanks.put(p, rankToSet);
								p.admin(rankToSet.equals("moderator") || rankToSet.equals("admin"));
								player.sendMessage("[green]Gave " + p.name + " [green]" + rankToSet + "!");
								p.sendMessage("[green]You have a new rank (" + rankToSet + ")! Re-log to fully apply it.");
								return;
							}
						}
					}
					mysqlConnection.close();
				}
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.infoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});
	}
}
