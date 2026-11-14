@echo off
chcp 65001 >nul
title Asistente del stick (ADB)
setlocal

where adb >nul 2>nul
if errorlevel 1 (
  echo No encuentro adb. Descarga "SDK Platform-Tools" de Google, descomprime
  echo y copia este .bat dentro de esa carpeta, o añadela al PATH.
  pause
  exit /b 1
)

set /p IP=IP del stick (Ajustes ^> Red):

:menu
echo.
echo ===== Asistente del stick =====
echo 1. Conectar con el stick
echo 2. Instalar / actualizar el launcher (lumen-launcher.apk en esta carpeta)
echo 3. Ver quien puede ser pantalla de inicio (HOME)
echo 4. Desactivar un paquete (p. ej. el launcher de fabrica)
echo 5. Reactivar un paquete (si algo sale mal)
echo 6. Listar apps instaladas
echo 7. Conceder permiso para el DNS sin anuncios (una sola vez)
echo 8. Salir
set /p OPC=Elige una opcion:

if "%OPC%"=="1" adb connect %IP%:5555 & goto menu
if "%OPC%"=="2" adb -s %IP%:5555 install -r lumen-launcher.apk & goto menu
if "%OPC%"=="3" adb -s %IP%:5555 shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME & goto menu
if "%OPC%"=="4" goto desactivar
if "%OPC%"=="5" goto reactivar
if "%OPC%"=="6" adb -s %IP%:5555 shell pm list packages -3 & goto menu
if "%OPC%"=="7" adb -s %IP%:5555 shell pm grant com.alvaro.launcher android.permission.WRITE_SECURE_SETTINGS & goto menu
if "%OPC%"=="8" exit /b 0
goto menu

:desactivar
set /p PKG=Paquete a desactivar (NO el com.alvaro.launcher):
adb -s %IP%:5555 shell pm disable-user --user 0 %PKG%
goto menu

:reactivar
set /p PKG=Paquete a reactivar:
adb -s %IP%:5555 shell pm enable %PKG%
goto menu
