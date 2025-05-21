package org.mryd.simple;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.mryd.VoiceConnector;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.audio.codec.CodecException;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.provider.*;
import su.plo.voice.api.server.audio.source.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

import static org.mryd.VoiceConnector.instance;

public class SimpleAddon implements VoicechatPlugin {

    private final Map<UUID, PlayerAudioContext> playerContexts = new ConcurrentHashMap<>();
    private final PlasmoVoiceServer plasmoVoice = instance.getPlasmo().getVoiceServer();

    @Getter
    public static VoicechatServerApi simpleApi;

    @Override
    public String getPluginId() {
        return "voice_wav_saver";
    }

    @Override
    public void initialize(VoicechatApi api) {
        instance.getLogger().info("VoiceChat WAV Saver initialized");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        simpleApi = event.getVoicechat();
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || sender.getPlayer() == null) return;
        UUID uuid = sender.getPlayer().getUuid();
        byte[] encoded = event.getPacket().getOpusEncodedData();

        PlayerAudioContext context = playerContexts.computeIfAbsent(uuid, id -> createContext(id, event.getVoicechat()));
        if (context == null) return;

        short[] samples = context.decoder.decode(encoded);
        if (samples != null) {
            context.enqueue(samples);
        }
    }

    private PlayerAudioContext createContext(UUID uuid, VoicechatServerApi api) {
        OpusDecoder decoder = api.createDecoder();
        ServerEntitySource source = VoiceConnector.plasmoSources.get(uuid);

        if (source == null || decoder == null || plasmoVoice == null) return null;

        BlockingQueue<short[]> sampleQueue = new LinkedBlockingDeque<>();

        AudioEncoder encoder = plasmoVoice.createOpusEncoder(false); // mono
        Encryption encryption = plasmoVoice.getDefaultEncryption();

        AudioFrameProvider frameProvider = new EncryptedFrameProvider(sampleQueue, encoder, encryption);
        AudioSender sender = source.createAudioSender(frameProvider, (short) 16);

        sender.start();

        sender.onStop(() -> {
            playerContexts.remove(uuid);
            try {
                encoder.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        return new PlayerAudioContext(decoder, sender, sampleQueue);
    }

    private static class PlayerAudioContext {
        final OpusDecoder decoder;
        final AudioSender sender;
        final BlockingQueue<short[]> sampleQueue;

        PlayerAudioContext(OpusDecoder decoder, AudioSender sender, BlockingQueue<short[]> queue) {
            this.decoder = decoder;
            this.sender = sender;
            this.sampleQueue = queue;
        }

        void enqueue(short[] samples) {
            sampleQueue.offer(samples);
        }
    }

    private static class EncryptedFrameProvider implements AudioFrameProvider {

        private final BlockingQueue<short[]> queue;
        private final ByteBuffer buffer = ByteBuffer.allocate(960 * 2); // 20ms mono @ 48kHz
        private final AudioEncoder encoder;
        private final Encryption encryption;

        public EncryptedFrameProvider(BlockingQueue<short[]> queue, AudioEncoder encoder, Encryption encryption) {
            this.queue = queue;
            this.encoder = encoder;
            this.encryption = encryption;
        }

        @Override
        public @NotNull AudioFrameResult provide20ms() {
            buffer.clear();
            int requiredSamples = 960;

            try {
                while (buffer.position() < requiredSamples * 2) {
                    short[] samples = queue.poll(5, TimeUnit.MILLISECONDS);
                    if (samples == null) break;

                    for (short sample : samples) {
                        if (buffer.remaining() < 2) break;
                        buffer.putShort(sample);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AudioFrameResult.Finished.INSTANCE;
            }

            while (buffer.position() < requiredSamples * 2) {
                buffer.putShort((short) 0);
            }

            short[] frameSamples = new short[requiredSamples];
            buffer.flip();
            for (int i = 0; i < requiredSamples; i++) {
                frameSamples[i] = buffer.getShort();
            }

            try {
                byte[] encoded = encoder.encode(frameSamples);
                byte[] encrypted = encryption.encrypt(encoded);
                return new AudioFrameResult.Provided(encrypted);
            } catch (CodecException e) {
                System.err.println("Opus encode failed: " + e.getMessage());
                return AudioFrameResult.Finished.INSTANCE;
            } catch (EncryptionException e) {
                System.err.println("Encryption failed: " + e.getMessage());
                return AudioFrameResult.Finished.INSTANCE;
            }
        }
    }
}
