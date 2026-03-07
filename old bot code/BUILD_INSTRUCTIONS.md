# Build Instructions

## Prerequisites

1. **Java 17 JDK** - Download from:
   - Oracle: https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
   - OpenJDK: https://adoptium.net/temurin/releases/?version=17

2. Verify Java installation:
   ```
   java -version
   ```
   Should show version 17.x.x

## Building the Mod

### Windows:

1. Open Command Prompt in the project directory
2. Run the build:
   ```
   gradlew.bat build
   ```

3. Wait for the build to complete (first build downloads dependencies, takes 5-10 minutes)

4. Find your mod JAR in:
   ```
   build\libs\playerbot-1.0.0.jar
   ```

## Setting Up Development Environment

### For IntelliJ IDEA:

```
gradlew.bat genIntellijRuns
```

Then open the project in IntelliJ and import the Gradle project.

### For Eclipse:

```
gradlew.bat genEclipseRuns
```

Then import as an existing Gradle project.

## Testing the Mod

1. After building, copy `build\libs\playerbot-1.0.0.jar` to your Forge server's `mods\` folder

2. Start your Minecraft 1.20.1 Forge server (offline mode)

3. Join the server and run:
   ```
   /op YourUsername
   /bot spawn TestBot
   ```

4. You should see TestBot join the server!

## Troubleshooting

**"Java version not compatible"**
- Ensure Java 17 is installed and set as JAVA_HOME

**"Task failed"**
- Delete `.gradle` folder and `build` folder
- Run `gradlew.bat clean build` again

**"Cannot find Forge"**
- Check your internet connection
- Gradle needs to download Forge and dependencies

**Bot doesn't spawn**
- Ensure server is in offline mode
- Check you have OP permissions (level 2+)
- Check server logs for errors
