@echo off
chcp 65001 >nul
cd /d "%~dp0"
node outils\construire-instance.mjs
pause
