# Control TV

Tenía problemas con el control remoto de mi TV: se perdió, no conseguía uno de reemplazo
ni una app que funcionara con ella. Así que decidí hacer mi propio control remoto.

## Descargar

**[Descarga el APK para Android desde Releases](../../releases/latest)**. Pásalo al
celular, ábrelo y permite "instalar apps desconocidas". Si aparece Play Protect, toca
"Más detalles" → "Instalar de todos modos" (sale porque la app no viene de la Play Store).

## Qué es

Control remoto casero para TV Hisense con sistema **VIDAA** (probado en una Riviera
RLED-DSU55HIK6155, fabricada por Hisense). Funciona por WiFi, sin el control original.

Usa el servicio de control remoto que la TV abre en el puerto **36669** (MQTT), el mismo
que usa la app oficial de Hisense.

## Qué incluye

| Parte | Qué es | Necesita la PC prendida |
|---|---|---|
| `android/` | App Android nativa que habla directo con la TV | No |
| `control_tv.py` + `control.html` | Servidor web: abre el control en cualquier navegador | Sí |

Botones: encendido, flechas y OK, atrás, inicio, menú, volumen (se repite al mantener),
canales, silencio, reproducción, números y cambio de entrada (HDMI, AV, antena).

Las dos versiones **buscan la TV solas** en la red si cambia su dirección.

## Configuración (opcional)

Copia `config.example.json` a `config.json` y pon la IP y la MAC de tu TV.
`config.json` está en `.gitignore`: tus datos de red no se suben.

- Sin `tv_ip`, la TV se busca automáticamente en la red.
- `tv_mac` solo sirve para el botón **Encender** (Wake-on-LAN). Muchas TV no lo admiten;
  si no prende, usa el botón físico.

## Versión web (PC)

```bash
pip install paho-mqtt
python control_tv.py
```

Abre en el celular la dirección que aparece (misma red WiFi). En Windows también está
`Abrir control TV.bat`. Si el firewall pregunta, permite Python en redes privadas.

## App Android

Se compila sin Android Studio ni Gradle, solo con JDK 17 y el Android SDK
(`build-tools;34.0.0` y `platforms;android-34`):

```bash
cd android
JDK=/ruta/al/jdk/bin ANDROID_SDK=/ruta/al/android-sdk bash build.sh
```

Genera `ControlTV.apk` en la carpeta principal, firmado con una clave local
(`android/controltv.keystore`, creada en la primera compilación y fuera de git).
Para instalarlo, pásalo al celular y permite "instalar apps desconocidas".

## Notas

- El usuario y la clave del servicio MQTT que aparecen en el código son los valores
  públicos del protocolo de Hisense, no datos personales.
- Al apagar la TV su servicio de red se detiene; volver a encenderla por red depende del modelo.
- Proyecto casero, sin relación con Hisense ni Riviera.
