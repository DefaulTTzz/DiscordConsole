package dev.discordconsole;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class DiscordConsole extends JavaPlugin {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_CHUNK = 1900; // Discord limit is 2000 incl. code fence

    private JDA jda;
    private String channelId;
    private Set<String> allowedUsers;
    private Set<String> allowedRoles;
    private Set<String> blocked;

    private final Queue<String> lines = new ConcurrentLinkedQueue<>();
    private ConsoleAppender appender;
    private BukkitTask flushTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!start()) {
            getLogger().severe("DiscordConsole is not configured correctly. Edit config.yml and run /discordconsole reload.");
        }
    }

    @Override
    public void onDisable() {
        stop();
    }

    private boolean start() {
        reloadConfig();
        String token = getConfig().getString("token", "");
        channelId = getConfig().getString("channel-id", "");
        allowedUsers = Set.copyOf(getConfig().getStringList("allowed-user-ids"));
        allowedRoles = Set.copyOf(getConfig().getStringList("allowed-role-ids"));
        blocked = getConfig().getStringList("blocked-commands").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        if (token.isBlank() || token.startsWith("PUT_YOUR")) return false;
        if (channelId.isBlank() || channelId.equals("000000000000000000")) return false;

        try {
            jda = JDABuilder.createLight(token,
                            GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new Listener())
                    .build();
        } catch (Exception e) {
            getLogger().severe("Could not log in to Discord: " + e.getMessage());
            return false;
        }

        if (getConfig().getBoolean("relay-console", true)) {
            appender = new ConsoleAppender();
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);

            long ticks = Math.max(1, getConfig().getInt("flush-interval-seconds", 2)) * 20L;
            flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::flush, ticks, ticks);
        }
        return true;
    }

    private void stop() {
        if (flushTask != null) { flushTask.cancel(); flushTask = null; }
        if (appender != null) {
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(appender);
            appender.stop();
            appender = null;
        }
        if (jda != null) { jda.shutdown(); jda = null; }
        lines.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            stop();
            sender.sendMessage(start() ? "DiscordConsole reloaded." : "DiscordConsole reload failed, check console.");
            return true;
        }
        return false;
    }

    // ---- Console -> Discord ----

    private void flush() {
        if (jda == null || lines.isEmpty()) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        List<String> batch = new ArrayList<>();
        String l;
        while ((l = lines.poll()) != null) batch.add(l);

        StringBuilder chunk = new StringBuilder();
        for (String line : batch) {
            // Split very long lines
            while (line.length() > MAX_CHUNK) {
                send(channel, chunk);
                chunk.setLength(0);
                send(channel, new StringBuilder(line.substring(0, MAX_CHUNK)));
                line = line.substring(MAX_CHUNK);
            }
            if (chunk.length() + line.length() + 1 > MAX_CHUNK) {
                send(channel, chunk);
                chunk.setLength(0);
            }
            chunk.append(line).append('\n');
        }
        send(channel, chunk);
    }

    private void send(TextChannel channel, StringBuilder text) {
        if (text.length() == 0) return;
        channel.sendMessage("```\n" + text + "```").queue(null, t -> {});
    }

    private class ConsoleAppender extends AbstractAppender {
        ConsoleAppender() {
            super("DiscordConsoleAppender", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String logger = event.getLoggerName();
            if (logger != null && (logger.startsWith("net.dv8tion") || logger.startsWith("okhttp3"))) return;
            String msg = event.getMessage().getFormattedMessage();
            if (msg == null) return;
            msg = msg.replaceAll("\u001B\\[[;\\d]*m", "")   // strip ANSI colors
                     .replaceAll("§[0-9a-fk-or]", "")       // strip MC color codes
                     .replace("```", "'''");                // don't break the code block
            lines.add("[" + LocalTime.now().format(TIME) + " " + event.getLevel().name() + "] " + msg);
        }
    }

    // ---- Discord -> Console ----

    private boolean isAuthorized(Member member) {
        if (allowedUsers.contains(member.getId())) return true;
        for (Role r : member.getRoles()) {
            if (allowedRoles.contains(r.getId())) return true;
        }
        return false;
    }

    private class Listener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot() || !e.isFromGuild()) return;
            if (!e.getChannel().getId().equals(channelId)) return;

            Member member = e.getMember();
            if (member == null || !isAuthorized(member)) return;

            String command = e.getMessage().getContentRaw().trim();
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isEmpty()) return;

            String first = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (blocked.contains(first)) {
                e.getMessage().reply("`" + first + "` is blocked from Discord.").queue();
                return;
            }

            final String finalCommand = command;
            getLogger().info(e.getAuthor().getName() + " ran from Discord: /" + finalCommand);
            Bukkit.getScheduler().runTask(DiscordConsole.this,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand));
            e.getMessage().addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("✅")).queue();
        }
    }
}
