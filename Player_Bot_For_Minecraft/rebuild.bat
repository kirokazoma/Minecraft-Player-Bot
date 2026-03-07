@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%

echo Cleaning old classes...
del /Q build\classes\java\main\com\playerbot\*.class 2>nul
del /Q build\classes\java\main\com\playerbot\command\*.class 2>nul
del /Q build\classes\java\main\com\playerbot\bot\*.class 2>nul

echo Compiling Java sources...
javac -cp ".gradle\loom-cache\remapped_mods\net_minecraftforge_forge_1_20_1-47_3_0_universal.jar;build\loom-cache\launch_classpath\*" -d build\classes\java\main src\main\java\com\playerbot\*.java src\main\java\com\playerbot\command\*.java src\main\java\com\playerbot\bot\*.java

if errorlevel 1 (
    echo Compilation failed!
    pause
    exit /b 1
)

echo Copying resources...
xcopy /Y /I src\main\resources\META-INF build\classes\java\main\META-INF\

echo Creating JAR...
cd build\classes\java\main
jar cf ..\..\..\..\build\libs\playerbot-1.0.0-dev.jar *
cd ..\..\..\..\

echo Done! JAR created at build\libs\playerbot-1.0.0-dev.jar
pause
