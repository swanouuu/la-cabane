@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
for /d %%D in ("%~dp0.outils\jdk21\*") do set "JAVA_HOME=%%D"
if not defined JAVA_HOME (
  echo Java 21 introuvable dans .outils\jdk21 : lance d'abord installer-outils.bat
  pause & exit /b 1
)
echo === Compilation de SakaiMod ===
call mod-cabane\gradlew.bat -p mod-cabane build --no-configuration-cache
if errorlevel 1 (
  echo La compilation a échoué.
  pause & exit /b 1
)
del /q pack\mods\cabane-*.jar 2>nul
copy /y mod-cabane\build\libs\cabane-*.jar pack\mods\ >nul
pushd pack
"..\.outils\gopath\bin\packwiz.exe" refresh
popd
echo Mod compilé et ajouté au pack. Lance publier.bat pour l'envoyer à tout le monde.
pause
