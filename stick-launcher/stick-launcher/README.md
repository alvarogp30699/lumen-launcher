# Lumen Launcher (v0.2)

Launcher propio para el stick MX10-F3 (Android TV), sin anuncios ni recomendaciones.

- **Inicio:** hora, "Seguir viendo", Lumen IPTV, SmartTube, DAZN, Downloader, Play Store, Ajustes y todas las apps.
- **Lumen IPTV:** lista Xtream Codes con categorías, favoritos, guía (ahora / a continuación) y reproductor en directo.
  La app no lleva ninguna lista ni credencial incluida: se configura desde el móvil con un QR.
- **Sin anuncios:** interruptor de DNS AdGuard para todo el sistema (hay que dar un permiso una vez, ver abajo).
- **Extras:** calidad Auto/4K/1080p, temporizador, estado del stick (RAM, almacenamiento, Wi-Fi), liberar memoria,
  fijar/mover/ocultar apps y autoactualización desde GitHub.
- Se registra como pantalla de inicio (HOME): arranca solo al encender el stick.

## 1. Compilar el APK (en GitHub)

1. Crea un repositorio **público** en GitHub (no contiene listas IPTV ni datos personales).
2. Sube todo el contenido de esta carpeta a la rama `main`.
3. En la pestaña **Actions** se lanza "Compilar APK" (3-5 minutos).
4. Al terminar, el APK queda en **Releases → última versión → lumen-launcher.apk**.
   Enlace fijo: `https://github.com/TU_USUARIO/TU_REPO/releases/latest/download/lumen-launcher.apk`

Cada cambio que subas genera una versión nueva (v1.N). El APK va firmado con una clave fija
(`app/launcher.keystore`), así que las actualizaciones se instalan encima.

## 2. Instalarlo en el stick

Con **Downloader**: escribe el enlace de arriba, descarga e instala (activa "Instalar apps desconocidas" para Downloader).
O desde el PC con `tools/stick.bat` (opción 2), con la depuración ADB activada en el stick:
Ajustes → Acerca de → pulsa 7 veces "Compilación" → Opciones de desarrollador → Depuración USB/ADB.

## 3. Hacerlo el inicio por defecto

Pulsa Home en el mando: Android pregunta qué pantalla de inicio usar. Elige **Lumen Launcher → Siempre**.
Si sigue saliendo el de fábrica: `tools/stick.bat` → opción 3 (ver paquetes HOME) y opción 4 (desactivar el de fábrica).
Si algo falla, la opción 5 lo reactiva.

## 4. Configurar la lista IPTV

Inicio → Lumen IPTV → "Configurar desde el móvil" (o Ajustes → Configurar IPTV). Escanea el QR con el móvil
(misma Wi-Fi) y rellena servidor (`http://dominio:puerto`), usuario y contraseña. En el mismo formulario
puedes poner el repositorio de GitHub para las actualizaciones (`usuario/repositorio`).

En la parrilla: **mantén OK pulsado** (o Menú) sobre un canal para marcarlo como favorito.
En el reproductor: ▲/▼ cambian de canal y OK muestra la información.
Si un canal no carga, prueba el otro formato en Ajustes (m3u8 / ts).

## 5. DNS sin anuncios

Una sola vez, con el stick conectado por ADB (`tools/stick.bat` → opción 7):

    adb shell pm grant com.alvaro.launcher android.permission.WRITE_SECURE_SETTINGS

Después, Ajustes → "Sin anuncios (DNS AdGuard)" lo activa o desactiva.
Bloquea anuncios y rastreadores a nivel de DNS; no puede quitar la publicidad que una app muestre dentro de sus propios vídeos.

## Nota

Escrito sin poder compilarlo ni probarlo en un dispositivo. Si GitHub Actions da error o algo falla en el stick,
copia el mensaje y se corrige.
