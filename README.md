
# StatusPlugin

### The Ultimate Status & Chat Management Solution

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk) ![Paper](https://img.shields.io/badge/Paper-1.21+-blue?style=for-the-badge&logo=paper) ![Version](https://img.shields.io/badge/Version-7.0.1-green?style=for-the-badge) ![License](https://img.shields.io/badge/License-Mit_WITH_RESTRICTIONS-red?style=for-the-badge)

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
| `/status` | `statusplugin.command.status` | Open the status selection GUI |
| `/status-clear` | `statusplugin.command.clear` | Reset your status |
| `/status-preview` | `statusplugin.command.preview` | Preview how you look in chat |
| `/status-admin` | `statusplugin.admin` | Admin management commands |

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

* ✅ You **CAN** modify the code for your own personal use or server.
* ❌ You **CANNOT** distribute or share modified versions.

See the `LICENSE` file for details.
