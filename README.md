# StatusPlugin

A lightweight Minecraft server plugin that provides configurable player status features, placeholders, and a built‑in Modrinth update check. Built with Maven.


## Features
- Configurable player status options and messages
- Placeholder expansion for use in other plugins
- Simple YAML configuration (no database required)
- Modrinth version checker to notify about updates
- Optional DiscordSRV relay for chat (keeps in-game formatting unchanged)

## Requirements
- Java 17+
- Paper (Spigot) compatible server (1.19+ recommended)


## Installation
1. Download or build the plugin JAR (see Build below).
2. Place the JAR into your server's `plugins/` directory.
3. Start the server once to generate default configuration files.
4. Edit the configuration files to your liking (see Configuration).
5. Reload or restart the server.



## Configuration
After the first run, the following files are created in your server `plugins/StatusPlugin/` folder:
- `config.yml`: Core settings for the plugin
### DiscordSRV Relay

If you use DiscordSRV, this plugin can relay chat to Discord without changing in-game behavior.

- Ensure DiscordSRV is installed.
- `discordsrv-relay-enabled: true` (default) controls the relay.
- This plugin adds `softdepend: [DiscordSRV]` to load safely after DiscordSRV.

- `status-options.yml`: Define available player status options
- `player-status.yml`: Stores current player status selections
- `language.yml`: Customize messages sent to players


## Placeholders
The plugin registers status-related placeholders that you can use in compatible plugins (e.g., scoreboards, chat, tab lists). Example usage depends on your placeholder manager. 
Refer to your placeholder system's docs and the keys you define in `status-options.yml`.

If your server uses PlaceholderAPI, ensure it is installed and that this plugin's expansion is recognized. 
Use: `STATUSPLUGIN_STATUS` as placeholder!


## Update Checks
A lightweight Modrinth version checker is included. When an update is available, a console log (and optionally in‑game notice for players with permission) will be shown on server start.
