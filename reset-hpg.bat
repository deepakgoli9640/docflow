@echo off
REM Stops all HPG containers and deletes their volumes (fresh database on next start).
REM Everything stored locally is lost: hospitals, payments, refunds, ledger, emails.
title HPG - reset
cd /d "%~dp0"
echo This deletes ALL local HPG data: hospitals, payments, refunds, ledger and emails.
echo Use stop-hpg.bat instead if you only want to stop HPG.
echo.
choice /C YN /M "Delete all local HPG data"
if errorlevel 2 (
    echo Cancelled. Nothing was deleted.
    echo.
    pause
    exit /b 0
)
docker compose -f deploy/docker-compose.yml --profile "*" down -v
echo.
echo Reset finished. Run start-hpg.bat for a fresh start.
pause
