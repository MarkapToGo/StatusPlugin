# Changelog

All notable changes to the **StatusPlugin** project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [7.0.10] - 2026-09-06

### Added
- **Status Suggestion System**:
  - `/status-suggest <format>`: Allows players to submit custom status suggestions directly in-game.
  - `/status-suggest info`: Provides guidelines, submission limits, syntax examples, and live preview rendering.
  - Bracket requirement enforcement (must start with `[` and end with `]`), configurable in `config.yml`.
  - Configurable minimum/maximum length constraints (excluding color/formatting tags) and submission throttling per player.
  - Interactive admin review tools:
    - `/status-admin requests list` featuring interactive MiniMessage click actions (`[✔ Accept]`, `[✖ Deny]`).
    - `/status-admin requests accept <id> <name>`: Automatically injects accepted status into `status-options.yml`, assigns it to the submitter, and notifies them.
    - `/status-admin requests deny <id> <reason>`: Rejects the submission with an admin-specified explanation.
  - Offline player notification delivery upon reconnection for approved or rejected requests.
  - Persistent data storage for suggestion tracking in `status-requests.yml`.
- **Testing & Quality Assurance**:
  - Configured JUnit 5 (`junit-jupiter`) in Gradle build script.
  - Added unit test suite (`ColorUtilTest`) validating MiniMessage gradients, Bukkit legacy hex format (`&x&r&r...`), bracket detection, and suggestion lifecycle records.
- **Permission Declarations**:
  - Added explicit permission trees to `paper-plugin.yml` (`statusplugin.admin`, `statusplugin.reload`, `statusplugin.mod`).

### Changed
- **Platform & Toolchain Migration**:
  - Updated target Paper API to `26.2.build.+` with `api-version: '26.2'`.
  - Configured Gradle Java toolchain for Java 25.
  - Enabled compiler warnings (`-Xlint:all`, `-Xlint:-processing`) and resource filtering for `paper-plugin.yml`.
- **Scoreboard Team & Sorting Management**:
  - Overhauled team management in `TabListManager` and `NametagManager` to prevent team collision and scoreboard pollution.
  - Added startup and shutdown cleanup routines targeting stale `sp_` and `sp_sort_` scoreboard teams.
  - Enforced thread-safe operations for scoreboard modifications and tab list header/footer updates.
- **Color & Placeholder Utilities**:
  - Added support for Bukkit legacy hex syntax (`&x&6&5&F&F&6&4`), standard hex (`&#rrggbb`), and MiniMessage tags.
  - Hardened `PlaceholderUtil` against asynchronous execution with main-thread synchronization dispatch.
  - Added HTTP resource disposal (`OkHttpClient` connection pool & dispatcher cleanup) in `VersionChecker.shutdown()`.

### Fixed
- Fixed integer overflow potential with rotating tab list index (`rotatingIndex.get() & Integer.MAX_VALUE`).
- Fixed duplicate read timeout initialization in `VersionChecker`.
- Fixed tab list list-name async invocation warning by checking `Bukkit.isPrimaryThread()`.

---

## [7.0.9] - 2026-01-18

### Added
- Name coloring for chat and player tab list.
- Configurable color presets for player display names.

---

## [7.0.8] - 2026-01-15

### Changed
- Excluded vanished players (via Vanish API / metadata) from online player count placeholders.

---

## [7.0.7] - 2026-01-12

### Added
- Modular chat color formatting and permission-based color options.

---

## [7.0.6] - 2026-01-10

### Added
- Admin chat color configuration and dedicated rank colors.

---

## [7.0.5] - 2026-01-08

### Changed
- Added safeguards and configuration documentation regarding scoreboard team conflicts with external tab/nametag plugins.

---

## [7.0.4] - 2026-01-05

### Added
- Configuration toggle to enable or disable the version update checker.

---

## [7.0.2] - 2026-01-02

### Added
- PlaceholderAPI support and compatibility improvements for SimpleNicks.

---

## [7.0.1] - 2026-01-01

### Added
- Initial complete rewrite for Paper 1.20+ with native MiniMessage formatting.
- `AsyncChatEvent` integration via custom non-canceling `ChatRenderer`.
- Dynamic tab list formatting with header/footer, rotating announcements, and status sorting.
- Optional scoreboard nametag system with death message and join message cleaning.
- Persistent death tracker with administration commands.
- Geographic country display via GeoIP / IP lookup integration.
- Soft-dependency hooks for PlaceholderAPI, TAB, and LibertyBans.
