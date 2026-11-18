# Control TV

Tenía problemas con el control remoto de mi TV: se perdió, no conseguía uno de reemplazo
ni una app que funcionara con ella. Así que decidí hacer mi propio control remoto.

## Descargar

**[Descarga el APK para Android desde Releases](../../releases/latest)**. Pásalo al
celular, ábrelo y permite "instalar apps desconocidas". Si aparece Play Protect, toca
"Más detalles" → "Instalar de todos modos" (sale porque la app no viene de la Play Store).

## Qué es

Control remoto casero por WiFi, sin el control original. Funciona con dos tipos de TV:

| Tipo de TV | Probado en | Cómo se conecta |
|---|---|---|
| **Hisense VIDAA** | Riviera RLED-DSU55HIK6155 (fabricada por Hisense) | Servicio de control de la TV, puerto 36669 (MQTT) |
| **Android TV / Google TV** | Riviera RLED-AND43HIKA32E (Hisense, Android TV) | Protocolo oficial "Android TV Remote v2", puertos 6466/6467, con **código de vinculación** |

Con Android TV, la primera vez la TV muestra un código de 6 caracteres; lo escribes en el
control y queda vinculado. Además el texto se envía **directo**: el teclado sirve en cualquier
app (buscadores, contraseñas…), no solo en YouTube.

## Qué incluye

| Parte | Qué es | Necesita la PC prendida |
|---|---|---|
| `android/` | App Android nativa que habla directo con la TV | No |
| `control_tv.py` + `control.html` | Servidor web: abre el control en cualquier navegador | Sí |

Botones: encendido, flechas y OK, atrás, inicio, menú, volumen (se repite al mantener),
canales, silencio, reproducción, números y cambio de entrada (HDMI, AV, antena).

Las dos versiones **buscan la TV solas** en la red si cambia su dirección.

| Función | App Android | Web (PC) |
|---|:-:|:-:|
| Botones y entradas | ✅ | ✅ |
| **Flechas o joystick** (el joystick repite más rápido cuanto más lo arrastras; toque = OK) | ✅ | ✅ |
| **Acceso rápido a Netflix y YouTube** | ✅ | ✅ |
| Teclado (VIDAA: en YouTube · Android TV: en cualquier app) | ✅ | ✅ |
| **Mando por voz** | ✅ | — |
| **Varias TVs** (buscar, elegir, renombrar) | ✅ | ✅ |
| **Android TV con código de vinculación** | ✅ | ✅ |

### 🎤 Mando por voz (Android)

Toca **Voz** y habla (usa el reconocimiento de voz de Google del celular). Ejemplos:

- "sube el volumen", "baja el volumen cinco", "silencio", "apaga la tele"
- "abre Netflix", "pon YouTube", "abre el navegador"
- "HDMI 2", "pon la antena", "canal 12", "siguiente canal"
- "pausa", "reproduce", "atrás", "inicio", "abajo tres veces", "ok"
- "busca música relajante" (con la búsqueda de YouTube abierta), "escribe hola"

### 📺 Varias TVs (Android)

Toca el nombre de la TV arriba para ver la lista: **Buscar TVs en mi WiFi** encuentra todas
las Hisense/VIDAA prendidas; puedes elegir cuál controlar, cambiarle el nombre (✎) o quitarla (✕).
Si hay varias TVs y la elegida cambia de dirección, la app pide elegir en vez de adivinar.

### ⌨ Teclado para YouTube

La TV no acepta texto por la red, así que el teclado de la app **escribe moviendo el
cursor** por el teclado en pantalla de YouTube, letra por letra (unos 0,5 s por pulsación).

1. En la TV abre YouTube y entra a la búsqueda (🔍); el cursor queda en la **A**.
2. En la app escribe lo que buscas y toca **Escribir y buscar**.

Admite letras, ñ, tildes (se escriben sin tilde) y espacios. Los números todavía no.
Si mueves el cursor a mano con las flechas, vuelve a dejarlo en la A antes de escribir.
El mapa del teclado está en `teclado_youtube.py` / `TecladoYouTube.java`; si tu YouTube
muestra otro orden de letras, ajusta `FILAS`.

## Configuración (opcional)

Copia `config.example.json` a `config.json` y pon la IP y la MAC de tu TV.
`config.json` está en `.gitignore`: tus datos de red no se suben.

- Sin `tv_ip`, la TV se busca automáticamente en la red.
- `tv_mac` solo sirve para el botón **Encender** (Wake-on-LAN). Muchas TV no lo admiten;
  si no prende, usa el botón físico.
- `tv_nombre` es el nombre con que aparece la TV en la app (por defecto "Mi TV").

## Versión web (PC)

```bash
pip install paho-mqtt androidtvremote2
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
