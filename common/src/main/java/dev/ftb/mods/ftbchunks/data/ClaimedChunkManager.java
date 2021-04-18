package dev.ftb.mods.ftbchunks.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.util.UUIDTypeAdapter;
import dev.ftb.mods.ftbchunks.FTBChunks;
import me.shedaniel.architectury.hooks.LevelResourceHooks;
import me.shedaniel.architectury.platform.Platform;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author LatvianModder
 */
public class ClaimedChunkManager {
	public static final LevelResource DATA_DIR = LevelResourceHooks.create("data/ftbchunks");

	public final MinecraftServer server;
	public UUID serverId;
	public final Map<UUID, ClaimedChunkPlayerData> playerData;
	public final Map<ChunkDimPos, ClaimedChunk> claimedChunks;
	public Path dataDirectory;
	public Path localDirectory;
	private boolean inited;

	public ClaimedChunkManager(MinecraftServer s) {
		server = s;
		serverId = UUID.randomUUID();
		playerData = new HashMap<>();
		claimedChunks = new HashMap<>();
		inited = false;
	}

	public void init() {
		if (inited) {
			return;
		}

		inited = true;

		long nanos = System.nanoTime();
		dataDirectory = server.getWorldPath(DATA_DIR);
		localDirectory = Platform.getGameFolder().resolve("local/ftbchunks");

		try {
			if (Files.notExists(dataDirectory)) {
				Files.createDirectories(dataDirectory);
			}

			if (Files.notExists(localDirectory)) {
				Files.createDirectories(localDirectory);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		Path infoFile = dataDirectory.resolve("info.json");

		if (Files.exists(infoFile)) {
			try (Reader reader = Files.newBufferedReader(infoFile)) {
				JsonObject json = new GsonBuilder().disableHtmlEscaping().create().fromJson(reader, JsonObject.class);
				serverId = UUID.fromString(json.get("id").getAsString());
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		} else {
			try (Writer writer = Files.newBufferedWriter(infoFile)) {
				JsonObject json = new JsonObject();
				json.addProperty("id", serverId.toString());
				new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(json, writer);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		loadPlayerData();
		int forceLoaded = 0;

		for (ClaimedChunk chunk : claimedChunks.values()) {
			if (chunk.isForceLoaded() && chunk.getPlayerData().chunkLoadOffline()) {
				forceLoaded++;
				chunk.postSetForceLoaded(true);
			}
		}

		FTBChunks.LOGGER.info("Server " + serverId + ": Loaded " + claimedChunks.size() + " chunks (" + forceLoaded + " force loaded) from " + playerData.size() + " players in " + ((System.nanoTime() - nanos) / 1000000D) + "ms");
		getServerData();
	}

	public void serverSaved() {
		for (ClaimedChunkPlayerData data : playerData.values()) {
			if (data.shouldSave) {
				try (Writer writer = Files.newBufferedWriter(data.file)) {
					FTBChunks.GSON.toJson(data.toJson(), writer);
				} catch (Exception ex) {
					ex.printStackTrace();
				}

				data.shouldSave = false;
			}
		}
	}

	private void loadPlayerData() {
		try {
			Files.list(dataDirectory).filter(path -> path.getFileName().toString().endsWith(".json") && !path.getFileName().toString().equals("info.json") && !path.getFileName().toString().equals("known_fake_players.json")).forEach(path -> {
				try (Reader reader = Files.newBufferedReader(path)) {
					JsonObject json = FTBChunks.GSON.fromJson(reader, JsonObject.class);

					if (json == null || !json.has("name") || !json.has("uuid")) {
						return;
					}

					UUID id = UUIDTypeAdapter.fromString(json.get("uuid").getAsString());

					ClaimedChunkPlayerData data = new ClaimedChunkPlayerData(this, path, id);
					data.fromJson(json);
					playerData.put(id, data);
				} catch (Exception ex) {
					FTBChunks.LOGGER.error("Failed to load " + path + ": " + ex + ". Deleting the file...");

					try {
						Files.delete(path);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public MinecraftServer getMinecraftServer() {
		return server;
	}

	public UUID getServerId() {
		return serverId;
	}

	public ClaimedChunkPlayerData getData(UUID id, String name) {
		ClaimedChunkPlayerData data = playerData.get(id);

		if (data == null) {
			data = new ClaimedChunkPlayerData(this, dataDirectory.resolve(UUIDTypeAdapter.fromUUID(id) + "-" + name + ".json"), id);
			data.profile = new GameProfile(id, name);
			playerData.put(id, data);
			data.save();
		}

		return data;
	}

	public ClaimedChunkPlayerData getData(ServerPlayer player) {
		return getData(player.getUUID(), player.getGameProfile().getName());
	}

	public ClaimedChunkPlayerData getServerData() {
		return getData(Util.NIL_UUID, "Server");
	}

	@Nullable
	public ClaimedChunk getChunk(ChunkDimPos pos) {
		return claimedChunks.get(pos);
	}

	public Collection<ClaimedChunk> getAllClaimedChunks() {
		return claimedChunks.values();
	}

	public static String prettyTimeString(long seconds) {
		if (seconds <= 0L) {
			return "0 seconds";
		}

		StringBuilder builder = new StringBuilder();
		prettyTimeString(builder, seconds, true);
		return builder.toString();
	}

	private static void prettyTimeString(StringBuilder builder, long seconds, boolean addAnother) {
		if (seconds <= 0L) {
			return;
		} else if (!addAnother) {
			builder.append(" and ");
		}

		if (seconds < 60L) {
			builder.append(seconds);
			builder.append(seconds == 1L ? " second" : " seconds");
		} else if (seconds < 3600L) {
			builder.append(seconds / 60L);
			builder.append(seconds / 60L == 1L ? " minute" : " minutes");

			if (addAnother) {
				prettyTimeString(builder, seconds % 60L, false);
			}
		} else if (seconds < 86400L) {
			builder.append(seconds / 3600L);
			builder.append(seconds / 3600L == 1L ? " hour" : " hours");

			if (addAnother) {
				prettyTimeString(builder, seconds % 3600L, false);
			}
		} else {
			builder.append(seconds / 86400L);
			builder.append(seconds / 86400L == 1L ? " day" : " days");

			if (addAnother) {
				prettyTimeString(builder, seconds % 86400L, false);
			}
		}
	}
}