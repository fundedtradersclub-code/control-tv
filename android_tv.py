"""Control de TVs Android TV / Google TV para la version web (PC).

Usa la libreria androidtvremote2 (protocolo oficial "Android TV Remote v2"):
la primera vez la TV muestra un codigo de 6 caracteres para vincular.
Requiere:  pip install androidtvremote2
"""
import asyncio
import threading
from pathlib import Path

from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth

DIR = Path(__file__).parent
CERT = str(DIR / "atv_cert.pem")   # identidad de este control (local, no se sube a git)
KEY = str(DIR / "atv_key.pem")

# KEY_* del control -> teclas de Android
TECLAS = {
    "KEY_POWER": "POWER", "KEY_UP": "DPAD_UP", "KEY_DOWN": "DPAD_DOWN", "KEY_LEFT": "DPAD_LEFT",
    "KEY_RIGHT": "DPAD_RIGHT", "KEY_OK": "DPAD_CENTER", "KEY_RETURNS": "BACK", "KEY_EXIT": "BACK",
    "KEY_HOME": "HOME", "KEY_MENU": "MENU", "KEY_VOLUMEUP": "VOLUME_UP", "KEY_VOLUMEDOWN": "VOLUME_DOWN",
    "KEY_MUTE": "VOLUME_MUTE", "KEY_CHANNELUP": "CHANNEL_UP", "KEY_CHANNELDOWN": "CHANNEL_DOWN",
    "KEY_PLAY": "MEDIA_PLAY_PAUSE", "KEY_FORWARDS": "MEDIA_FAST_FORWARD", "KEY_BACK": "MEDIA_REWIND",
    "KEY_SUBTITLE": "CAPTIONS", **{f"KEY_{d}": str(d) for d in range(10)},
}
FUENTES = [{"id": "TV_INPUT_HDMI_1", "nombre": "HDMI1"}, {"id": "TV_INPUT_HDMI_2", "nombre": "HDMI2"},
           {"id": "TV_INPUT_HDMI_3", "nombre": "HDMI3"}, {"id": "TV", "nombre": "TV"}]
APPS = {"netflix": "https://www.netflix.com/title", "youtube": "https://www.youtube.com"}


class AndroidTV:
    """Una conexion a la vez; corre en su propio hilo con asyncio."""

    def __init__(self):
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self.remote = None
        self.ip = None
        self.conectado = False
        self.pedir_codigo = False
        self.mensaje = ""

    def _correr(self, coro, espera=20):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(espera)

    # ---- conexion y vinculacion ----

    def conectar(self, ip):
        """Conecta; si la TV no esta vinculada, la deja mostrando el codigo."""
        try:
            self._correr(self._conectar(ip))
        except Exception as e:   # noqa: BLE001
            self.conectado = False
            self.mensaje = f"No se pudo conectar ({type(e).__name__})"
        return self.conectado

    async def _conectar(self, ip):
        if self.remote:
            self.remote.disconnect()
        self.ip, self.conectado, self.pedir_codigo = ip, False, False
        r = AndroidTVRemote("Control TV (PC)", CERT, KEY, ip)
        self.remote = r
        await r.async_generate_cert_if_missing()
        r.add_is_available_updated_callback(self._disponible)
        try:
            await r.async_connect()
        except InvalidAuth:
            await r.async_start_pairing()
            self.pedir_codigo = True
            self.mensaje = "Escribe el código que aparece en la TV"
            return
        except (CannotConnect, ConnectionClosed):
            self.mensaje = "TV apagada o sin red"
            return
        r.keep_reconnecting()
        self.conectado, self.mensaje = True, ""

    def _disponible(self, disponible):
        self.conectado = disponible
        if not disponible:
            self.mensaje = "TV apagada o sin red"

    def codigo(self, codigo):
        try:
            self._correr(self._codigo(codigo.strip().upper()))
            return True
        except InvalidAuth:
            self.mensaje = "Código incorrecto. Revisa el código de la TV."
        except Exception as e:   # noqa: BLE001
            self.pedir_codigo = False
            self.mensaje = f"La vinculación falló ({type(e).__name__}). Elige la TV otra vez."
        return False

    async def _codigo(self, codigo):
        await self.remote.async_finish_pairing(codigo)
        self.pedir_codigo = False
        await self.remote.async_connect()
        self.remote.keep_reconnecting()
        self.conectado, self.mensaje = True, ""

    def cancelar(self):
        self.pedir_codigo = False
        self.desconectar()

    def desconectar(self):
        if self.remote:
            r, self.remote = self.remote, None
            self.loop.call_soon_threadsafe(r.disconnect)
        self.conectado = False

    # ---- ordenes ----

    def _llamar(self, fn, *args):
        if not self.remote or not self.conectado:
            return False
        try:
            self._correr(self._en_loop(fn, *args), 5)
            return True
        except Exception:   # noqa: BLE001
            return False

    @staticmethod
    async def _en_loop(fn, *args):
        fn(*args)

    def tecla(self, k):
        t = TECLAS.get(k)
        return bool(t) and self._llamar(self.remote.send_key_command, t) if self.remote else False

    def fuente(self, id_):
        return self._llamar(self.remote.send_key_command, id_) if self.remote else False

    def abrir(self, nombre):
        enlace = APPS.get(nombre.lower())
        return bool(enlace) and self._llamar(self.remote.send_launch_app_command, enlace) if self.remote else False

    def escribir(self, texto, con_buscar):
        if not self.remote:
            return False
        ok = True
        if texto:
            ok = self._llamar(self.remote.send_text, texto)
        if ok and con_buscar:
            ok = self._llamar(self.remote.send_key_command, "ENTER")
        return ok
