@echo off
REM Stops all HPG containers and deletes their volumes (fresh database on next start).
title HPG - reset
cd /d "%~dp0"
docker compose -f deploy/docker-compose.yml --profile "*" down -v
echo.
echo Reset finished. You can close this window.
pause
