@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo   SoundX - recompilation complete
echo ============================================
call mvnw.cmd clean package
echo.
if errorlevel 1 (
    echo [ERREUR] Build echoue.
) else (
    echo OK -^> target\soundx-1.0.0.jar
    echo Vos sons et vos reglages ne sont PAS touches
    echo ^(ils vivent dans %%LOCALAPPDATA%%\SoundX^).
)
pause
