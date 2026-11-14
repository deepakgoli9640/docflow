@echo off
REM Double-click to build and start the whole project (needs Docker Desktop).
title HPG - starting
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\start.ps1"
echo.
pause
