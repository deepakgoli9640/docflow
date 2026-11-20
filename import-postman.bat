@echo off
REM Double-click to put every HPG endpoint into Postman (collection + "HPG local" environment).
REM   import-postman.bat        import into Postman
REM   import-postman.bat run    import, then run the whole collection with newman (needs Node.js)
title HPG - Postman
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\postman.ps1" %*
echo.
pause
