@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo === Publication des mods pour tout le monde ===
node outils\publier.mjs %*
pause
