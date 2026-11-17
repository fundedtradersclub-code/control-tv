"""Control remoto web para la TV Riviera/Hisense (RLED-DSU55HIK6155).

Uso:  python control_tv.py
Luego abre en el celular (misma red WiFi) la direccion que se muestra en pantalla.
Requiere:  pip install paho-mqtt
"""
import json, socket, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import paho.mqtt.client as mqtt
from teclado_youtube import Teclado

PUERTO_WEB = 8765
PAUSA_TECLA = 0.5   # segundos entre pulsaciones al escribir
CONFIG = Path(__file__).parent / "config.json"   # local, no se sube a git


def leer_config():
    try:
        return json.loads(CONFIG.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def guardar_config(cfg):
    CONFIG.write_text(json.dumps(cfg, indent=2), encoding="utf-8")


def red_local():
    """IP de esta PC en la red de la casa (sin enviar nada)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]


def buscar_tv():
    """Recorre la red local buscando el puerto de control de las TV Hisense."""
    from concurrent.futures import ThreadPoolExecutor
    base = red_local().rsplit(".", 1)[0] + "."

    def probar(ip):
        try:
            with socket.create_connection((ip, 36669), timeout=0.4):
                return ip
        except OSError:
            return None

    with ThreadPoolExecutor(64) as pool:
        for ip in pool.map(probar, (base + str(i) for i in range(1, 255))):
            if ip:
                return ip
    return None


def ip_responde(ip):
    try:
        with socket.create_connection((ip, 36669), timeout=1.5):
            return True
    except OSError:
        return False


cfg = leer_config()
TV_IP = cfg.get("tv_ip")
if not TV_IP or not ip_responde(TV_IP):
    print("Buscando la TV en la red…")
    TV_IP = buscar_tv() or TV_IP
    if TV_IP:
        cfg["tv_ip"] = TV_IP
        guardar_config(cfg)
if not TV_IP:
    raise SystemExit("No encontré la TV. ¿Está prendida y en el mismo WiFi?")
TV_MAC = cfg.get("tv_mac", "")    # opcional, para intentar encenderla por red
CLIENT_ID = "controlpc"
# Usuario/clave publicos del protocolo de control de Hisense (no son datos tuyos)
MQTT_USER, MQTT_PASS = "hisenseservice", "multimqttservice"

T_KEY = f"/remoteapp/tv/remote_service/{CLIENT_ID}$normal/actions/sendkey"
T_SRC = f"/remoteapp/tv/ui_service/{CLIENT_ID}$normal/actions/changesource"
T_SRCLIST = f"/remoteapp/tv/ui_service/{CLIENT_ID}$normal/actions/sourcelist"

estado = {"conectado": False, "fuente": None, "fuentes": []}
cliente = None
lock = threading.Lock()


def on_connect(c, ud, flags, rc, props=None):
    estado["conectado"] = not rc.is_failure
    if estado["conectado"]:
        c.subscribe("/remoteapp/mobile/#")
        c.publish(T_SRCLIST, "")


def on_disconnect(c, ud, flags, rc, props=None):
    estado["conectado"] = False


def on_message(c, ud, m):
    try:
        data = json.loads(m.payload or b"null")
    except ValueError:
        return
    if m.topic.endswith("/sourcelist") and isinstance(data, list):
        estado["fuentes"] = [{"id": s.get("sourceid"), "nombre": s.get("displayname") or s.get("sourcename")} for s in data]
    elif m.topic.endswith("ui_service/state") and isinstance(data, dict) and data.get("statetype") == "sourceswitch":
        estado["fuente"] = data.get("displayname")


def conectar():
    global cliente
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=CLIENT_ID)
    c.username_pw_set(MQTT_USER, MQTT_PASS)
    c.on_connect, c.on_disconnect, c.on_message = on_connect, on_disconnect, on_message
    c.reconnect_delay_set(1, 10)
    try:
        c.connect(TV_IP, 36669, 10)
    except OSError:
        pass  # la TV puede estar apagada; loop_start reintenta
    c.loop_start()
    cliente = c


def enviar_tecla(tecla):
    with lock:
        if not estado["conectado"]:
            try:
                cliente.reconnect()
                time.sleep(1)
            except OSError:
                return False
        return cliente.publish(T_KEY, tecla).rc == 0


teclado = Teclado()
escritura = {"activa": False, "hecho": 0, "total": 0, "detener": False}


def escribir_en_tv(texto, con_buscar):
    if escritura["activa"]:
        return False
    seq = teclado.escribir(texto) + (teclado.buscar() if con_buscar else [])
    if not seq:
        return False
    escritura.update(activa=True, hecho=0, total=len(seq), detener=False)

    def trabajo():
        try:
            for k in seq:
                if escritura["detener"]:
                    teclado.reiniciar()   # se detuvo a medias: la posicion ya no es segura
                    break
                enviar_tecla(k)
                escritura["hecho"] += 1
                time.sleep(PAUSA_TECLA)
        finally:
            escritura["activa"] = False

    threading.Thread(target=trabajo, daemon=True).start()
    return True


def cambiar_fuente(sid):
    return cliente.publish(T_SRC, json.dumps({"sourceid": sid})).rc == 0


def encender_wol():
    if not TV_MAC:
        return False
    mac = bytes.fromhex(TV_MAC.replace(":", "").replace("-", ""))
    paquete = b"\xff" * 6 + mac * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for destino in ("255.255.255.255", TV_IP.rsplit(".", 1)[0] + ".255"):
            s.sendto(paquete, (destino, 9))
    return True


PAGINA = (Path(__file__).parent / "control.html").read_text(encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/estado":
            return self._json({**estado, "escribiendo": escritura["activa"],
                               "escritoHecho": escritura["hecho"], "escritoTotal": escritura["total"]})
        body = PAGINA.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        largo = int(self.headers.get("Content-Length") or 0)
        try:
            datos = json.loads(self.rfile.read(largo) or b"{}")
        except ValueError:
            return self._json({"ok": False}, 400)
        if self.path == "/tecla" and str(datos.get("tecla", "")).startswith("KEY_"):
            teclado.reiniciar()  # el cursor se movio a mano: volver a suponer la A
            return self._json({"ok": enviar_tecla(datos["tecla"])})
        if self.path == "/escribir":
            texto = str(datos.get("texto", ""))[:200]
            return self._json({"ok": escribir_en_tv(texto, bool(datos.get("buscar")))})
        if self.path == "/detener":
            escritura["detener"] = True
            return self._json({"ok": True})
        if self.path == "/fuente" and datos.get("id"):
            return self._json({"ok": cambiar_fuente(str(datos["id"]))})
        if self.path == "/encender":
            return self._json({"ok": encender_wol()})
        self._json({"ok": False}, 404)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    conectar()
    print(f"Control de TV listo. Abre en tu celular:  http://{red_local()}:{PUERTO_WEB}")
    print("Para cerrar: Ctrl+C")
    ThreadingHTTPServer(("0.0.0.0", PUERTO_WEB), Handler).serve_forever()
