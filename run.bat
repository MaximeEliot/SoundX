@echo off
setlocal
cd /d "%~dp0"

REM Compile seulement si le jar n'existe pas encore.
if not exist "target\soundx-1.0.0.jar" (
    echo Premiere compilation ^(peut prendre 1-2 min^)...
    call mvnw.cmd -q clean package
    if errorlevel 1 (
        echo.
        echo [ERREUR] Le build Maven a echoue.
        pause
        exit /b 1
    )
)

start "" javaw -jar "target\soundx-1.0.0.jar"
