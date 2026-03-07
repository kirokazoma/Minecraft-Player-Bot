# PlayerBot - Minecraft 1.20.1 Forge Mod

Server-side mod that creates player bots that behave like real players.

## Features

- Server-side only (no client mod required)
- Bots appear as real players in the player list
- Offline mode compatible
- Full operator control via commands
- Bots spawn at world spawn point

## Requirements

- Minecraft 1.20.1
- Forge 47.3.0+
- Java 17+
- Server must be in offline mode

## Building

1. Ensure Java 17 is installed
2. Run: `gradlew.bat build`
3. Find the mod JAR in `build/libs/`

## Installation

1. Build the mod or download the JAR
2. Place in your server's `mods/` folder
3. Start the server

## Commands

All commands require OP level 2 (operator permissions):

- `/bot spawn <name>` - Creates a new bot with the specified name
- `/bot remove <name>` - Removes the specified bot
- `/bot list` - Lists all active bots
- `/bot removeall` - Removes all bots from the server

## Usage Examples

```
/bot spawn TestBot1
/bot spawn Worker
/bot list
/bot remove TestBot1
/bot removeall
```

## Technical Details

- Bots are full ServerPlayer entities
- They have fake network connections that pass server validation
- They appear in the player list and tab menu
- They spawn in survival mode at world spawn
- They don't timeout or get kicked
- Server treats them as legitimate players

## Development Setup

1. Clone this repository
2. Run `gradlew.bat genIntellijRuns` (for IntelliJ) or `gradlew.bat genEclipseRuns` (for Eclipse)
3. Import the project into your IDE
4. Run the server configuration to test

## Notes

- Bots currently spawn and idle at spawn point
- Future updates will add AI behavior, movement, and interaction
- Designed for offline mode servers (no authentication required)
- Each bot gets a unique UUID based on their name

## License

MIT
