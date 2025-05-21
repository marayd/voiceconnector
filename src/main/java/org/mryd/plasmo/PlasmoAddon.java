package org.mryd.plasmo;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.mryd.simple.SimpleAddon;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.audio.codec.AudioDecoder;
import su.plo.voice.api.audio.codec.CodecException;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.server.player.BaseVoicePlayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Addon(
        id = "pv-addon-connector",
        name = "connector",
        version = "1.0.0",
        authors = {"mryd"},
        scope = AddonLoaderScope.ANY
)
public class PlasmoAddon implements AddonInitializer {

    @InjectPlasmoVoice
    @Getter
    private PlasmoVoiceServer voiceServer;

    private static Encryption encryption;

    private static final long BUFFER_DURATION_NS = 300_000_000;
    private static final int MAX_AUDIO_BUFFER_SIZE = 4096 * 10;

    private static class AudioBuffer {
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_AUDIO_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        private long lastFlushTime = System.nanoTime();

        public synchronized void append(byte[] data) {
            if (buffer.remaining() < data.length) {
                flush();
            }
            buffer.put(data, 0, Math.min(data.length, buffer.remaining()));
        }

        public synchronized boolean shouldFlush() {
            return (System.nanoTime() - lastFlushTime) >= BUFFER_DURATION_NS || buffer.position() >= MAX_AUDIO_BUFFER_SIZE;
        }

        public synchronized byte[] flush() {
            int size = buffer.position();
            if (size == 0) return new byte[0];
            buffer.flip();
            byte[] data = new byte[size];
            buffer.get(data);
            buffer.clear();
            lastFlushTime = System.nanoTime();
            return data;
        }
    }

    private final ConcurrentHashMap<String, AudioDecoder> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AudioBuffer> speakingBuffers = new ConcurrentHashMap<>();

    @Override
    public void onAddonInitialize() {
        voiceServer.getEventBus().register(this, PlayerSpeakEvent.class, EventPriority.HIGHEST, this::onPlayerSpeak);
        voiceServer.getEventBus().register(this, PlayerSpeakEndEvent.class, EventPriority.HIGHEST, this::onPlayerSpeakEnd);
        encryption = voiceServer.getDefaultEncryption();
    }

    private AudioDecoder getOrCreateDecoder(String playerId) {
        return decoders.computeIfAbsent(playerId, id -> voiceServer.createOpusDecoder(false));
    }

    private void releaseResources(String playerId) {
        var decoder = decoders.remove(playerId);
        if (decoder != null) decoder.close();
        speakingBuffers.remove(playerId);
    }

    private void onPlayerSpeak(PlayerSpeakEvent event) {
        byte[] encryptedFrame = event.getPacket().getData();
        Player player = event.getPlayer().getInstance().getInstance();
        String playerId = ((BaseVoicePlayer<?>) event.getPlayer()).getInstance().getName();

        try {
            byte[] decrypted = encryption.decrypt(encryptedFrame);
            short[] audioFrame = getOrCreateDecoder(playerId).decode(decrypted);

            ByteBuffer byteBuffer = ByteBuffer.allocate(audioFrame.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : audioFrame) {
                byteBuffer.putShort(s);
            }

            byte[] audioBytes = byteBuffer.array();
            AudioBuffer buffer = speakingBuffers.computeIfAbsent(playerId, id -> new AudioBuffer());
            buffer.append(audioBytes);

            if (buffer.shouldFlush()) {
                byte[] combinedAudio = buffer.flush();
                sendAudioSamples(player, byteArrayToShortArray(combinedAudio));
            }

        } catch (EncryptionException | CodecException e) {
            e.printStackTrace();
        }
    }

    private void onPlayerSpeakEnd(PlayerSpeakEndEvent event) {
        String playerId = ((BaseVoicePlayer<?>) event.getPlayer()).getInstance().getName();
        releaseResources(playerId);
    }

    public static void sendAudioSamples(Player sourcePlayer, short[] samples) {
        VoicechatServerApi api = SimpleAddon.simpleApi;
        if (api == null) return;

        double radius = 16.0;

        for (Player targetPlayer : sourcePlayer.getWorld().getPlayers()) {
            if (targetPlayer.equals(sourcePlayer)) continue;

            if (targetPlayer.getLocation().distance(sourcePlayer.getLocation()) > radius) continue;

            VoicechatConnection targetConnection = api.getConnectionOf(targetPlayer.getUniqueId());
            if (targetConnection == null) continue;

            StaticAudioChannel channel = api.createStaticAudioChannel(UUID.randomUUID(), targetConnection.getPlayer().getServerLevel(), targetConnection);
            if (channel == null) continue;

            OpusEncoder encoder = api.createEncoder();
            AudioPlayer player = api.createAudioPlayer(channel, encoder, samples);
            player.startPlaying();
        }
    }

    private static short[] byteArrayToShortArray(byte[] byteArray) {
        if (byteArray.length % 2 != 0) {
            throw new IllegalArgumentException("Нечётное количество байтов, не может быть преобразовано в short[]");
        }

        short[] shortArray = new short[byteArray.length / 2];
        for (int i = 0; i < shortArray.length; i++) {
            int low = byteArray[i * 2] & 0xFF;
            int high = byteArray[i * 2 + 1] << 8;
            shortArray[i] = (short) (high | low);
        }
        return shortArray;
    }

}
