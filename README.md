# StatusPlugin v5.9.1

A Minecraft plugin for custom status displays in chat and tab list with full Paper & Spigot support.

## ✅ What's Fixed

### Version 5.9.1 Updates:
1. ✅ **Version Checker Fixed** - No more NullPointerException crashes
2. ✅ **Chat Status Display** - Status now shows before player name in chat (default_status_enabled set to true)
3. ✅ **Paper TPS Support** - Native Paper API for TPS monitoring
4. ✅ **MSPT Support** - Milliseconds per tick (Paper only)
5. ✅ **MiniMessage Support** - Modern text formatting alongside legacy & codes
6. ✅ **Config Auto-Update** - Safely adds new options without overwriting your settings
7. ✅ **Death Tracking** - Enhanced accuracy without dependencies

## 🚀 Quick Start

### Installation:
1. Drop the JAR into your `plugins/` folder
2. Start/restart your server
3. Status system is ready to use!

### Basic Commands:
- `/status <option>` - Set your status (e.g., `/status ADMIN`, `/status VIP`)
- `/status-clear` - Clear your status
- `/status-admin <player> <status>` - Set someone else's status (requires permission)
- `/reloadstatus` - Reload the plugin config

## 🎨 Status Formats

### Two formats supported:

**Legacy & Codes (traditional):**
```yaml
VIP: "&a[VIP]&r"
ADMIN: "&4[&l★ ADMIN&4]"
```

**MiniMessage (modern):**
```yaml
GRADIENT: "<gradient:red:blue>[GRADIENT]</gradient>"
RAINBOW: "<rainbow>[RAINBOW]</rainbow>"
FIRE: "<gradient:yellow:red>[FIRE]</gradient>"
```

Both work in chat AND tab list!

## 📝 Available Placeholders

Use in tab list or with PlaceholderAPI:

- `%tps%` - Current TPS
- `%mspt%` - Milliseconds per tick (Paper only)
- `%performance%` - Performance label (SMOOTH/STABLE/etc)
- `%total_deaths%` - Total player deaths
- `%online_players%` - Players online
- `%server_time%` - Current time

## 🔧 Configuration Files

- `config.yml` - Main configuration
- `status-options.yml` - Define your status options
- `tablist.yml` - Custom tab list header/footer
- `language.yml` - Messages and text

## 📊 Features

✅ Custom status system  
✅ Chat formatting with status  
✅ Tab list formatting with status  
✅ Tab list sorting (Admins → Mods → Players → AFK)  
✅ TPS & MSPT monitoring (Paper)  
✅ Death tracking  
✅ Country location (optional)  
✅ PlaceholderAPI integration  
✅ TAB plugin support  
✅ LibertyBans mute integration  
✅ DiscordSRV chat relay  

## 🐛 Fixed Issues

- ✅ Version checker crash (NullPointerException)
- ✅ Status not showing in chat
- ✅ TPS not working on Paper
- ✅ Config overwriting user settings
- ✅ Block tracking on Paper

## 📝 Important Notes

### Status Display in Chat:
The status will show in chat automatically IF:
1. `chat-styling-enabled: true` in config.yml (default)
2. `default_status_enabled: true` in config.yml (now enabled by default)
3. Player has set a status with `/status <option>` OR has default status

### On First Join:
Players will get the default status `[Player]` automatically.

### To Change Your Status:
Use `/status <option>` where `<option>` is from `status-options.yml`

Example: `/status ADMIN`, `/status VIP`, `/status AFK`

### Performance Monitoring:
- TPS works on both Paper and Spigot
- MSPT only works on Paper (shows N/A on Spigot)

## 🎯 Permissions

- `statusplugin.admin` - Use admin commands
- `statusplugin.mod` - Use MOD status
- `statusplugin.reload` - Reload config

## 💡 Examples

### Example Chat Format:
```yaml
chat-format: "%status% &r<$$PLAYER$$> &e%countrycode%"
```
Result: `[ADMIN] <PlayerName> US: Hello!`

### Example Tab List Format:
```yaml
tab-list-format: "&a%status% &r$$PLAYER$$ &8[&c☠ %deaths%&8]"
```
Result: `[VIP] PlayerName [☠ 5]`

## 🔗 Links

- Modrinth: https://modrinth.com/plugin/statusplugin-like-in-craftattack

## ⚙️ Support

If you encounter issues:
1. Check console for error messages
2. Verify config.yml settings
3. Try `/reloadstatus` command
4. Make sure you're using Paper or Spigot 1.14+

---

**Enjoy your fully-functional StatusPlugin!** 🎉
