@echo off
REM ============================================================
REM  Gnauri launcher - serves this folder over http:// so the
REM  app's fetch / manifest / service worker / Load-sample work.
REM  Just double-click this file.
REM ============================================================
setlocal
cd /d "%~dp0"
set PORT=8000
set URL=http://localhost:%PORT%/index.html

echo Starting a local server for Gnauri in:
echo   %cd%
echo.

REM --- Try Python 3 (py launcher) ---
where py >nul 2>nul
if %errorlevel%==0 (
    echo Using Python ^(py^) to serve on port %PORT% ...
    start "" "%URL%"
    py -m http.server %PORT%
    goto :eof
)

REM --- Try python on PATH ---
where python >nul 2>nul
if %errorlevel%==0 (
    echo Using Python to serve on port %PORT% ...
    start "" "%URL%"
    python -m http.server %PORT%
    goto :eof
)

REM --- Try Node (npx serve) ---
where npx >nul 2>nul
if %errorlevel%==0 (
    echo Using Node ^(npx serve^) on port %PORT% ...
    start "" "http://localhost:3000"
    npx --yes serve -l %PORT% .
    goto :eof
)

echo.
echo  Could not find Python or Node.js on this computer.
echo  Install either one, then double-click this file again:
echo     Python:  https://www.python.org/downloads/   (check "Add to PATH")
echo     Node.js: https://nodejs.org/
echo.
pause
endlocal
