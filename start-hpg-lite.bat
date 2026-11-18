@echo off
REM Double-click to build and start the core payment flow only - for laptops with 8 GB RAM (needs Docker Desktop).
title HPG - starting (lite)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\start.ps1" -Lite
echo.
pause
