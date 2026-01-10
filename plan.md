# AI Prompt: StatusPlugin v7 - Complete Recode

> **Purpose**: Use this prompt to create a completely new StatusPlugin from scratch, following modern best practices for Paper 1.20+.

---

## Project Overview

Create a **new Minecraft Paper plugin** called "StatusPlugin" from scratch. This is a complete recode focusing on modern Paper APIs, clean architecture, and maximum compatibility with other plugins.

| Requirement | Value |
|------------|-------|
| **Target Platform** | Paper 1.20+ (with full 1.21.x support) |
| **Java Version** | 17+ |
| **Build System** | Gradle |
| **Package** | `de.stylelabor.statusplugin` |

---

## Core Features

### 1. Status System

Players can assign themselves a configurable status prefix (like `[VIP]`, `[ADMIN]`, `[AFK]`) that displays in chat and tab list.

- Status options defined in `status-options.yml` using **MiniMessage format**
- Players set status via `/status <option>` command
- Status persists across restarts (stored in `player-status.yml`)
- Default status can be assigned to new players (configurable)
- Permission-based statuses:
  - `ADMIN` status requires `statusplugin.admin` permission
  - `MOD` status requires `statusplugin.mod` permission

**Commands:**

| Command | Description | Permission |
|---------|-------------|------------|
| `/status <option>` | Set your own status | None |
| `/status-clear` | Clear your status | None |
| `/status-admin set <player> <status>` | Set another player's status | `statusplugin.admin` |
| `/status-admin reload` | Reload configuration | `statusplugin.reload` |
| `/status-admin deaths <player> [view\|add\|remove\|set\|reset] [amount]` | Manage death counts | `statusplugin.admin` |

Full tab completion for all commands.

---

### 2. Chat Formatting

> [!IMPORTANT]
> **CRITICAL: Use Paper's modern chat system for maximum compatibility with other plugins.**

**Requirements:**

- Listen to `io.papermc.paper.event.player.AsyncChatEvent`
- Use a custom `ChatRenderer` to modify the chat format
- **DO NOT** cancel the event
- **DO NOT** manually broadcast messages
- Let the event continue so other plugins (DiscordSRV, LibertyBans, etc.) can process it

**Placeholders for chat format:**

- `<status>` - Player's status
- `<player>` - Player name
- `<message>` - The chat message
- `<deaths>` - Player's death count
- `<country>` / `<countrycode>` - Country info (if enabled)

**Additional:** Automatically make URLs clickable in chat messages.

---

### 3. Tab List Formatting

- Custom player list name format with status prefix
- Custom header and footer (multi-line support)
- Rotating messages feature
- Configurable refresh interval

**Available placeholders:**
`<status>`, `<player>`, `<deaths>`, `<country>`, `<countrycode>`, `<online>`, `<max>`, `<tps>`, `<tps_5m>`, `<tps_15m>`, `<mspt>`, `<performance>`, `<time>`, `<overworld>`, `<nether>`, `<end>`, `<total_deaths>`

**Tab List Sorting:** Sort players by status priority using scoreboard teams (ADMIN → MOD → Regular → AFK/CAM).

---

### 4. Nametag System (Optional)

When enabled, show status prefix above player heads using scoreboard teams.

Options:

- Toggle on/off
- Clean join messages (remove nametag formatting)
- Clean death messages (remove nametag formatting)

---

### 5. Death Tracking

- Per-player death count stored in `player-deaths.yml`
- Total server deaths stored in `server-stats.yml`
- Batched/delayed saves (don't save on every death)
- Admin commands to view/modify death counts
- Sync with vanilla `DEATHS` statistic

---

### 6. Country Location (Optional)

- Disabled by default
- IP geolocation using free APIs:
  - Primary: `http://ip-api.com/json/{ip}`
  - Fallback: `https://api.iplocation.net/?ip={ip}`
- **Async fetching** (don't block main thread)
- Cache results in `player-countries.yml`

---

### 7. PlaceholderAPI Integration

Register these placeholders under `statusplugin`:

- `%statusplugin_status%`
- `%statusplugin_deaths%`
- `%statusplugin_country%`
- `%statusplugin_countrycode%`
- `%statusplugin_performance%`
- `%statusplugin_mspt%`
- `%statusplugin_total_deaths%`
- `%statusplugin_total_deaths_raw%`

---

### 8. TAB Plugin Integration

When TAB plugin is present, use its API:

- `TabListFormatManager` for player list names
- `HeaderFooterManager` for header/footer

---

### 9. LibertyBans Integration (Optional)

When LibertyBans is present, provide custom styled mute notifications with reason and duration.

> [!NOTE]
> With proper `AsyncChatEvent` usage, LibertyBans will automatically block muted players. This integration is optional for prettier mute messages.

---

### 10. Additional Features

- **Version Checker**: Check Modrinth for updates
- **bStats Metrics**: Plugin ID 20901

---

## Architecture Requirements

### Project Structure

```
src/main/java/de/stylelabor/statusplugin/
├── StatusPlugin.java           # Main plugin class
├── manager/
│   ├── StatusManager.java      # Status storage and retrieval
│   ├── ChatManager.java        # Chat formatting with ChatRenderer
│   ├── TabListManager.java     # Tab list formatting
│   ├── NametagManager.java     # Scoreboard team management
│   ├── DeathTracker.java       # Death counting and persistence
│   └── CountryManager.java     # IP geolocation (async)
├── command/
│   ├── StatusCommand.java
│   ├── StatusClearCommand.java
│   └── StatusAdminCommand.java
├── listener/
│   ├── ChatListener.java       # AsyncChatEvent with ChatRenderer
│   └── PlayerListener.java     # Join/quit/death events
├── integration/
│   ├── PlaceholderAPIExpansion.java
│   ├── TabPluginIntegration.java
│   └── LibertyBansIntegration.java
├── config/
│   └── ConfigManager.java      # All YAML config handling
└── util/
    ├── ColorUtil.java          # MiniMessage parsing
    └── VersionChecker.java
```

### Design Principles

| Principle | Description |
|-----------|-------------|
| **Dependency Injection** | Pass managers through constructors, no static access |
| **Single Responsibility** | Each class has one clear purpose |
| **Async Operations** | File I/O and HTTP requests must be async |
| **Immutable Data** | Use records for data classes |
| **Null Safety** | Use `Optional` and `@Nullable`/`@NotNull` annotations |

### Text Formatting

- Use **Adventure API** (`net.kyori.adventure`) for all text
- Use **MiniMessage** format in configuration files
- Support legacy `&` codes (convert to MiniMessage internally)

---

## Configuration Files Needed

### config.yml

Main configuration with sections for:

- Command name
- Default status settings
- Chat format (MiniMessage)
- Tab list format and refresh interval
- Nametag settings
- Country location toggle
- Integration toggles

### status-options.yml

Status definitions in MiniMessage format, e.g.:

- `ADMIN: "<dark_red>[<bold>★ ADMIN</bold>]</dark_red>"`
- `VIP: "<green>[VIP]</green>"`

### tablist.yml

- Header lines (list)
- Footer lines (list)
- Rotating line entries and interval

### language.yml

All user-facing messages in MiniMessage format.

---

## Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| Paper API 1.20.4+ | provided | Server API |
| PlaceholderAPI | provided | Placeholder integration |
| TAB-API | provided | TAB plugin integration |
| bStats | compile/shade | Usage metrics |
| OkHttp | compile/shade | HTTP client for country lookup |
| org.json | compile/shade | JSON parsing |

---

## Soft Dependencies

List in plugin.yml:

- PlaceholderAPI
- TAB
- LibertyBans

---

## Testing Checklist

- [ ] Status commands work and persist
- [ ] Chat shows status prefix correctly
- [ ] **DiscordSRV still receives chat messages** (critical!)
- [ ] **LibertyBans mute blocking works** (critical!)
- [ ] Tab list formatting and sorting works
- [ ] Header/footer updates with placeholders
- [ ] Death tracking works
- [ ] Country lookup is async (no lag)
- [ ] PlaceholderAPI placeholders work
- [ ] TAB integration works when present
- [ ] Config reload works without restart
- [ ] No console errors
- [ ] No memory leaks on player quit
- [ ] Works on Paper 1.20.x and 1.21.x

---

## Summary

**Key Points:**

1. **Paper-native APIs** - Use `AsyncChatEvent` with `ChatRenderer`
2. **Never cancel chat events** - Maximum plugin compatibility
3. **Modular architecture** - Separate manager classes
4. **Modern Java 17+** - Records, Optional, CompletableFuture
5. **MiniMessage** - Modern text formatting in configs

The plugin must work seamlessly with DiscordSRV, LibertyBans, TAB, and PlaceholderAPI without conflicts.

---

> **Author**: Markap
