# DiscordConsole

Use your Bukkit/Spigot/Paper server console from a Discord channel.

## Build
Requires Java 17+ and Maven:

    mvn clean package

The ready-to-use jar is `target/DiscordConsole-1.0.0.jar` (JDA is bundled).

## Setup
1. Create a bot at https://discord.com/developers/applications > Bot. Copy the token.
2. On the Bot page, enable **MESSAGE CONTENT INTENT**.
3. Invite the bot to your server (OAuth2 > URL Generator > scope `bot`,
   permissions: View Channel, Send Messages, Read Message History, Add Reactions).
4. Put the jar in `plugins/`, start the server once, then edit
   `plugins/DiscordConsole/config.yml` (token, channel-id, allowed-user-ids / allowed-role-ids).
5. Run `/discordconsole reload` or restart.

Anything an allowed user types in that channel runs as a console command;
console output is posted back in code blocks.

## Security
Anyone allowed here has full console access. Keep the list short, keep the
channel private, and never share your bot token.
