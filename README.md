# XrayTracker

XrayTracker is a high-performance Minecraft server plugin designed to detect and investigate xray hackers. It monitors player mining patterns, calculates real-time suspicion levels, alerts staff members, and provides advanced administrative tools to visually inspect dug routes.

## Features

- Real-Time Suspicion Scoring: Dynamically tracks how fast players find ores, awarding bonuses based on mining depth, ore streak count, proximity, and block type.
- Staff Alerts: Instantly notifies moderators in game when a player exceeds suspicion thresholds.
- Discord Integration: Sends alerts to a configured Discord channel via webhooks.
- Dynamic Search and Filtering: Interactive in-game graphical user interface allowing staff to sort players by name, last active time, alert counts, and suspicion scores.
- Static Path Visualizer: Spawns fake blocks client-side to show administrators the exact blocks a player mined.
- Animated Path Playback: Spawns an invisible armor stand wearing the player's skull and holding a pickaxe that glides smoothly along their mining path, mimicking their exact digging sequence block-by-block with sound and particle effects.
- Interactive Replay Controls: Chat-based control panel buttons to pause, play, skip forward, rewind, cycle speed, or restart animated path replays.
- Multi-World Support: Automatically teleports administrators along with the replay stand if a player changes worlds or teleports far away.
- Efficient Storage: Built-in support for H2/SQLite local databases and MySQL databases for high-traffic servers.

## Commands

All commands require the xraytracker.admin permission.

- /xt: Opens the main graphical user interface displaying tracked players.
- /xt stats <player>: Prints detailed suspicion statistics and mining behavior analytics in chat.
- /xt top [amount]: Displays a list of the most suspicious players currently tracked.
- /xt triggers <player>: Opens a GUI listing all suspicion alert triggers logged for that player.
- /xt replay <player>: Initiates an animated block-by-block replay of the player's route.
- /xt clear <player>: Deletes all recorded mining history and suspicion levels for a player.
- /xt toggle: Mutes or unmutes staff-wide xray alerts in chat for the command executor.
- /xt stop: Instantly clears any active path highlights or playback sessions.
- /xt reload: Reloads values and database configurations from config.yml.

## Permissions

- xraytracker.admin: Allows execution of all commands and unlocks access to the inspector GUIs.
- xraytracker.bypass: Bypasses mining tracking and suspicion calculation (intended for staff or trusted players).

## Installation

1. Place the compiled XrayTracker.jar file into your server's plugins directory.
2. Restart or reload the server.
3. Configure the database credentials and suspicion thresholds in the generated config.yml.
4. Reload the configuration using the command /xt reload.

## Compilation

The plugin is built using Gradle. To compile a production jar without version suffixes:

1. Ensure Java 21 JDK is installed.
2. Run the command: ./gradlew build
3. Retrieve the compiled jar from the build/libs/ directory.
