@echo off
rem Lance CaDesk sous Windows.
rem Ce dossier est autonome : il contient le programme, ses bibliotheques et Java (jdk\windows-x64), et peut
rem etre deplace ou copie ou l'on veut. Les donnees sont dans data\, a cote de ce fichier.
rem   start.bat             lance l'application (sans fenetre de console)
rem   start.bat --console   la lance dans cette console, pour voir les messages d'erreur eventuels
setlocal EnableExtensions

set "ROOT=%~dp0"
set "JAVA_BIN=%ROOT%jdk\windows-x64\bin"
if exist "%JAVA_BIN%\javaw.exe" goto launch
echo Java est introuvable : "%JAVA_BIN%\javaw.exe"
echo Ce dossier doit contenir cadesk.jar, lib\ et jdk\windows-x64\ : est-il complet ?
pause
exit /b 1

:launch
set JAVA_ARGS=--module-path "%ROOT%cadesk.jar;%ROOT%lib;%ROOT%lib\windows" --add-modules copilot.sdk.runtime --enable-native-access=javafx.graphics,org.xerial.sqlitejdbc "-Dcadesk.home=%ROOT%." -m cadesk/io.github.pierre3119.cadesk.ui.Main

if /i "%~1"=="--console" goto console
start "CaDesk" "%JAVA_BIN%\javaw.exe" %JAVA_ARGS%
exit /b 0

:console
"%JAVA_BIN%\java.exe" %JAVA_ARGS%
exit /b %ERRORLEVEL%
