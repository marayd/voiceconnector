package org.mryd;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import io.papermc.paper.event.player.AsyncChatEvent;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.mryd.plasmo.PlasmoAddon;
import org.mryd.simple.SimpleAddon;
import su.plo.slib.api.server.entity.McServerEntity;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.ServerEntitySource;
import su.plo.voice.api.server.player.VoiceServerPlayer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mryd.simple.SimpleAddon.playerContexts;

public class VoiceConnector extends JavaPlugin implements Listener {

    @Getter
    private final PlasmoAddon plasmo = new PlasmoAddon();

    public static VoiceConnector instance;

    public static HashMap<UUID, ServerEntitySource> plasmoSources = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("╔══════════════════════════════════════════════════════════╗");
        getLogger().info("║                      VoiceConnector                      ║");
        getLogger().info("║                Real-time Voice Bridge                    ║");
        getLogger().info("╠══════════════════════════════════════════════════════════╣");
        getLogger().info("║ Developer : mryd (MrydDev)                               ║");
        getLogger().info("║ Website   : https://mryd.org                             ║");
        getLogger().info("║ GitHub    : https://github.com/marayd/voiceconnector     ║");
        getLogger().info("║ License   : Apache 2.0                                   ║");
        getLogger().info("╚══════════════════════════════════════════════════════════╝");

        try {
            instance = this;
            getLogger().info("Enabling...");
            getLogger().info("================");
            getLogger().info("Registering bukkit events");
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("===============");
            getLogger().info("Hooking into PlasmoVoice");
            if (isPluginNotAvailable("PlasmoVoice")) throw new IllegalArgumentException("PlasmoVoice is not available");
            PlasmoVoiceServer.getAddonsLoader().load(plasmo);
            getLogger().info("===============");
            getLogger().info("Hooking into SimpleVoice");
            if (isPluginNotAvailable("voicechat")) throw new IllegalArgumentException("SimpleVoiceChat is not available");
            BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
            if (service != null) {
                service.registerPlugin(new SimpleAddon());
            }
            getLogger().info("===============");
        } catch (Exception e) {
            getLogger().severe("Enabling failed");
            getLogger().severe(e.getMessage());
            getLogger().severe(Arrays.toString(e.getStackTrace()));
        } finally {
            getLogger().info("Enabled successfully");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // PLASMO START (FOR SIMPLE VOICE USERS)
        PlasmoVoiceServer plasmoVoiceServer = plasmo.getVoiceServer();

        ServerSourceLine sourceLine = plasmoVoiceServer.getSourceLineManager()
                .getLineByName("proximity")
                .orElseThrow(() -> new IllegalStateException("Proximity source line not found"));

        VoiceServerPlayer plasmoVoicePlayer = plasmoVoiceServer.getPlayerManager()
                .getPlayerByName(player.getName())
                .orElseThrow(() -> new IllegalStateException("Player not found"));

        if (!plasmoVoicePlayer.hasVoiceChat()) {

            McServerEntity entity = plasmoVoiceServer.getMinecraftServer().getEntityByInstance(player);

            ServerEntitySource plasmoSource = sourceLine.createEntitySource(entity, false);
            plasmoSources.put(player.getUniqueId(), plasmoSource);
        }
        // PLASMO END
    }




    @EventHandler
    public void onPlayerLeft(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plasmoSources.remove(player.getUniqueId());
        playerContexts.remove(player.getUniqueId());
    }

    private boolean isPluginNotAvailable(String pluginName) {
        return !Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }


}