@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
for /d %%D in ("%~dp0.outils\jdk21\*") do set "JAVA_HOME=%%D"
if not defined JAVA_HOME (
  echo Java 21 introuvable dans .outils\jdk21 : lance d'abord installer-outils.bat
  pause & exit /b 1
)
rem le fichier de captures automatiques de Claude ne doit pas se lancer pendant tes tests
del /q "mod-cabane\run\cabane-photos.txt" 2>nul
echo === Minecraft de test : tous les mods du pack + la derniere version du mod La Cabane ===
echo (la premiere fois, compter 1 a 2 minutes de chargement)
call mod-cabane\gradlew.bat -p mod-cabane runClient --no-configuration-cache
pause
