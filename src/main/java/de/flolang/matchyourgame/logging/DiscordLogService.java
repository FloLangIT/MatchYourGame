package de.flolang.matchyourgame.logging;

import de.flolang.matchyourgame.config.ConfigManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DiscordLogService {
    private static final int MAX_QUEUE_SIZE = 5_000;
    private static final int MAX_MESSAGE_LENGTH = 3_900;
    private static final LinkedBlockingDeque<LogEntry> QUEUE = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private static final ScheduledExecutorService SENDER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "discord-audit-log");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile JDA jda;
    private static volatile long channelId;
    private static volatile boolean started;

    private DiscordLogService() {}

    public static synchronized void initialize(JDA api) {
        jda = api;
        try {
            channelId = Long.parseLong(ConfigManager.getString("Discord.LogChannelID", "0"));
        } catch (NumberFormatException ignored) {
            channelId = 0;
        }
        if (started || channelId == 0) return;
        started = true;
        SENDER.scheduleWithFixedDelay(DiscordLogService::sendNext, 0, 1, TimeUnit.SECONDS);
        action("SYSTEM", "Discord-Audit-Log gestartet");
    }

    public static void action(String type, String details) {
        enqueue(new LogEntry("MYG · " + safe(type), safe(details), new Color(
                ConfigManager.getInt("Discord.DefaultEmbed.Color", 0x7c3aed))));
    }

    public static void error(String logger, String message, String throwable) {
        StringBuilder text = new StringBuilder("Logger: ").append(safe(logger))
                .append("\nMessage: ").append(safe(message));
        if (throwable != null && !throwable.isBlank()) text.append("\n").append(throwable);
        enqueue(new LogEntry("MYG · Fehler", text.toString(), new Color(0xdc2626)));
    }

    private static void enqueue(LogEntry entry) {
        if (channelId == 0 || entry == null || entry.description().isBlank()) return;
        if (!QUEUE.offerLast(entry)) {
            QUEUE.pollFirst();
            QUEUE.offerLast(entry);
        }
    }

    private static void sendNext() {
        LogEntry entry = QUEUE.pollFirst();
        if (entry == null) return;
        JDA api = jda;
        TextChannel channel = api == null ? null : api.getTextChannelById(channelId);
        if (channel == null) return;
        MessageEmbed embed = new EmbedBuilder().setTitle(trim(entry.title(), 250))
                .setDescription(trim(entry.description(), MAX_MESSAGE_LENGTH))
                .setColor(entry.color()).setTimestamp(Instant.now()).build();
        channel.sendMessageEmbeds(embed).queue(null, ignored -> {
            // Never log Discord log delivery failures through SLF4J; that would recursively create more log entries.
        });
    }

    private static String trim(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, max - 20) + "\n… gekürzt";
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replace("@everyone", "@​everyone").replace("@here", "@​here");
    }

    private record LogEntry(String title, String description, Color color) {}
}
