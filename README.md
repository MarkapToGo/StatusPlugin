
# StatusPlugin

### The Ultimate Status & Chat Management Solution

![Java](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk) ![Paper](https://img.shields.io/badge/Paper-26.2+-blue?style=for-the-badge&logo=paper) ![Version](https://img.shields.io/badge/Version-7.1.1-green?style=for-the-badge) ![License](https://img.shields.io/badge/License-CUSTOM_LICENSE-red?style=for-the-badge)

</div>

---

## ✨ Features

* **Advanced Formatting**: Full MiniMessage support (gradients, hex colors, hover events) for chat, tablist, and nametags..
* **Status System**: Create custom statuses (Admin, VIP, etc.) with unique prefixes.
* **Tab List Control**:
  * Animated headers/footers.
  * **Rotating Messages**: Add scrolling lines with `<rotating>`.
  * **Sorting**: Sort players by rank (Owner > Admin > Member > AFK > NO STATUS).
  * **Stats**: Display deaths, country, TPS, Ping ... .
* *Country Display**: Automatically show player flags/countries in tab/chat (GeoIP).
* **Death Tracking**: count and display player deaths with custom formatting `[☠ 5]`.
* **Integrations**:
  * **PlaceholderAPI**: Full support for placeholders.
  * **LibertyBans**: Custom mute/ban notification styling.
  * **TAB**: seamless compatibility if you prefer using TAB plugin.
  * **Use of Paper's modern chat system for maximum compatibility with other plugins.**

## 📦 Installation

1. Download the latest JAR.
2. Drop it into your server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/StatusPlugin/config.yml` to your liking.

## 🛠️ Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/status [option]` | None | View current status or set a new status |
| `/status-clear` | None | Clear your active status |
| `/status-preview <status>` | None | Preview how a status looks in chat |
| `/status-suggest <format>` | None | Suggest a custom status (`/status-suggest info` for help) |
| `/status-admin set <player> <status>` | `statusplugin.admin` | Set another player's status |
| `/status-admin reload` | `statusplugin.reload` / `statusplugin.admin` | Reload plugin configuration |
| `/status-admin deaths <player> <action> [amount]` | `statusplugin.admin` | View or modify player death counts |
| `/status-admin requests [list\|accept\|deny]` | `statusplugin.admin` | Review and manage player status suggestions |

### Status Permissions
* Individual status options can be restricted via permissions in `status-options.yml` (e.g. `statusplugin.admin`, `statusplugin.mod`).

## 🧩 Configuration

The plugin generates several config files:

* `config.yml`: Main settings (Chat, Integrations, GeoIP).
* `status-options.yml`: Define your statuses and their formats.
* `tablist.yml`: Configure header, footer, and player list format.
* `language.yml`: Translate all plugin messages.

## 🔄 Update Checker

We use **Modrinth** for updates.

* Admins get notified on join if a new version is available.
* Automatic version comparison against `paper-plugin.yml`.

## 📄 License

This project is licensed under a **Custom License**.

* ✅ You **CAN** modify the code and host source on GitHub.
* ❌ You **CANNOT** upload the plugin to platforms like SpigotMC, Modrinth, or CurseForge.

See the `LICENSE` file for details.
