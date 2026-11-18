"""Control remoto web para TVs Hisense VIDAA y Android TV / Google TV.

Uso:  python control_tv.py
Luego abre en el celular (misma red WiFi) la direccion que se muestra en pantalla.
Requiere:  pip install paho-mqtt androidtvremote2
"""
import json, socket, threading, time
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import urllib.request
import paho.mqtt.client as mqtt
from teclado_youtube import Teclado

PUERTO_WEB = 8765
PAUSA_TECLA = 0.65  # segundos entre pulsaciones al escribir (teclado de YouTube en VIDAA)
CONFIG = Path(__file__).parent / "config.json"   # local, no se sube a git
PUERTO_VIDAA, PUERTO_ATV = 36669, 6466


def leer_config():
    try:
        return json.loads(CONFIG.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def guardar_config():
    CONFIG.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")


def red_local():
    """IP de esta PC en la red de la casa (sin enviar nada)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]


def puerto_abierto(ip, puerto, espera=0.4):
    try:
        with socket.create_connection((ip, puerto), timeout=espera):
            return True
    except OSError:
        return False


def nombre_cast(ip):
    """Nombre que anuncia una TV con Chromecast integrado (puerto 8008)."""
    try:
        with urllib.request.urlopen(f"http://{ip}:8008/setup/eureka_info?params=name", timeout=1.5) as r:
            return json.load(r).get("name", "")
    except Exception:   # noqa: BLE001
        return ""


def buscar_tvs():
    """Busca TVs Hisense VIDAA y Android TV en la red: [(ip, tipo, nombre_anunciado)]."""
    base = red_local().rsplit(".", 1)[0] + "."

    def probar(ip):
        if puerto_abierto(ip, PUERTO_VIDAA):
            return (ip, "vidaa", "")
        if puerto_abierto(ip, PUERTO_ATV):
            return (ip, "atv", nombre_cast(ip))
        return None

    with ThreadPoolExecutor(64) as pool:
        return [r for r in pool.map(probar, (base + str(i) for i in range(1, 255))) if r]


# ---------------- lista de TVs (config.json) ----------------

cfg = leer_config()
if "tvs" not in cfg:   # formato anterior: una sola TV Hisense
    cfg["tvs"] = []
    if cfg.get("tv_ip"):
        cfg["tvs"].append({"ip": cfg["tv_ip"], "nombre": cfg.get("tv_nombre", "Mi TV"),
                           "tipo": "vidaa", "mac": cfg.get("tv_mac", "")})
        cfg["actual"] = cfg["tv_ip"]
lista_lock = threading.Lock()


def tv_actual():
    return next((t for t in cfg["tvs"] if t["ip"] == cfg.get("actual")), None)


def agregar_halladas(halladas):
    with lista_lock:
        for ip, tipo, anunciado in halladas:
            t = next((t for t in cfg["tvs"] if t["ip"] == ip), None)
            if t is None:
                fin = ip.rsplit(".", 1)[1]
                nombre = anunciado or ("Android TV " if tipo == "atv" else "TV ") + fin
                cfg["tvs"].append({"ip": ip, "nombre": nombre, "tipo": tipo, "mac": ""})
            else:
                t["tipo"] = tipo
        guardar_config()


# ---------------- Hisense VIDAA (MQTT) ----------------

CLIENT_ID = "controlpc"
# Usuario/clave publicos del protocolo de control de Hisense (no son datos tuyos)
MQTT_USER, MQTT_PASS = "hisenseservice", "multimqttservice"
T_KEY = f"/remoteapp/tv/remote_service/{CLIENT_ID}$normal/actions/sendkey"
T_SRC = f"/remoteapp/tv/ui_service/{CLIENT_ID}$normal/actions/changesource"
T_SRCLIST = f"/remoteapp/tv/ui_service/{CLIENT_ID}$normal/actions/sourcelist"
T_APPLIST = f"/remoteapp/tv/ui_service/{CLIENT_ID}$normal/actions/applist"
T_LAUNCH = f"/remoteapp/tv/ui_service/{CLIENT_ID}$normal/actions/launchapp"
apps = []
estado = {"conectado": False, "fuente": None, "fuentes": []}
cliente = None
lock = threading.Lock()


def on_connect(c, ud, flags, rc, props=None):
    estado["conectado"] = not rc.is_failure
    if estado["conectado"]:
        c.subscribe("/remoteapp/mobile/#")
        c.publish(T_SRCLIST, "")
        c.publish(T_APPLIST, "")


def on_disconnect(c, ud, flags, rc, props=None):
    estado["conectado"] = False


def on_message(c, ud, m):
    try:
        data = json.loads(m.payload or b"null")
    except ValueError:
        return
    if m.topic.endswith("/applist") and isinstance(data, list):
        apps[:] = data
    elif m.topic.endswith("/sourcelist") and isinstance(data, list):
        estado["fuentes"] = [{"id": s.get("sourceid"), "nombre": s.get("displayname") or s.get("sourcename")} for s in data]
    elif m.topic.endswith("ui_service/state") and isinstance(data, dict) and data.get("statetype") == "sourceswitch":
        estado["fuente"] = data.get("displayname")


def conectar_vidaa(ip):
    global cliente
    desconectar_vidaa()
    estado.update(conectado=False, fuente=None, fuentes=[])
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=CLIENT_ID)
    c.username_pw_set(MQTT_USER, MQTT_PASS)
    c.on_connect, c.on_disconnect, c.on_message = on_connect, on_disconnect, on_message
    c.reconnect_delay_set(1, 10)
    try:
        c.connect(ip, PUERTO_VIDAA, 10)
    except OSError:
        pass  # la TV puede estar apagada; loop_start reintenta
    c.loop_start()
    cliente = c


def desconectar_vidaa():
    global cliente
    if cliente is not None:
        try:
            cliente.loop_stop()
            cliente.disconnect()
        except Exception:   # noqa: BLE001
            pass
        cliente = None
    estado["conectado"] = False


def enviar_tecla_vidaa(tecla):
    with lock:
        if cliente is None:
            return False
        if not estado["conectado"]:
            try:
                cliente.reconnect()
                time.sleep(1)
            except OSError:
                return False
        return cliente.publish(T_KEY, tecla).rc == 0


def abrir_app_vidaa(nombre):
    for a in apps:
        if not a.get("isunInstalled") and str(a.get("name", "")).lower() == nombre.lower():
            carga = {"appId": a.get("appId", ""), "name": a["name"], "url": a.get("url", ""),
                     "urlType": a.get("urlType", 0), "storeType": a.get("storeType", 0)}
            return cliente.publish(T_LAUNCH, json.dumps(carga)).rc == 0
    if cliente is not None and estado["conectado"]:
        cliente.publish(T_APPLIST, "")   # quiza aun no llego la lista: pedirla de nuevo
    return False


teclado = Teclado()
escritura = {"activa": False, "hecho": 0, "total": 0, "detener": False, "mensaje": ""}


def escribir_vidaa(texto, con_buscar):
    """La TV VIDAA no acepta texto: se recorre el teclado en pantalla de YouTube."""
    if escritura["activa"]:
        return False
    seq = teclado.escribir(texto) + (teclado.buscar() if con_buscar else [])
    if not seq:
        return False
    escritura.update(activa=True, hecho=0, total=len(seq), detener=False, mensaje="")

    def trabajo():
        try:
            for k in seq:
                if escritura["detener"]:
                    teclado.reiniciar()   # se detuvo a medias: la posicion ya no es segura
                    escritura["mensaje"] = "Detenido. Vuelve a dejar el cursor de la TV en la A."
                    break
                if not enviar_tecla_vidaa(k):
                    teclado.reiniciar()
                    escritura["mensaje"] = "Se cortó la conexión mientras escribía. Deja el cursor en la A y vuelve a intentar."
                    break
                escritura["hecho"] += 1
                time.sleep(PAUSA_TECLA)
        finally:
            escritura["activa"] = False

    threading.Thread(target=trabajo, daemon=True).start()
    return True


# ---------------- Android TV ----------------

try:
    from android_tv import AndroidTV, FUENTES as FUENTES_ATV
    atv = AndroidTV()
except ImportError:   # falta: pip install androidtvremote2
    atv, FUENTES_ATV = None, []


# ---------------- TV elegida: cada orden va por el protocolo de su tipo ----------------

def es_atv():
    t = tv_actual()
    return bool(t and t.get("tipo") == "atv")


def elegir_tv(ip):
    t = next((t for t in cfg["tvs"] if t["ip"] == ip), None)
    if not t:
        return False
    cfg["actual"] = ip
    guardar_config()
    teclado.reiniciar()
    if t.get("tipo") == "atv":
        desconectar_vidaa()
        if atv is None:
            return False
        threading.Thread(target=atv.conectar, args=(ip,), daemon=True).start()
    else:
        if atv:
            atv.desconectar()
        conectar_vidaa(ip)
    return True


def tecla(k):
    teclado.reiniciar()  # el cursor se movio a mano: volver a suponer la A
    return atv.tecla(k) if es_atv() else enviar_tecla_vidaa(k)


def escribir(texto, con_buscar):
    if es_atv():
        ok = atv.escribir(texto, con_buscar)
        escritura["mensaje"] = "" if ok else "No se pudo escribir. ¿Hay un campo de texto abierto en la TV?"
        return ok
    return escribir_vidaa(texto, con_buscar)


def fuente(id_):
    if es_atv():
        return atv.fuente(id_)
    return cliente is not None and cliente.publish(T_SRC, json.dumps({"sourceid": id_})).rc == 0


def abrir(nombre):
    return atv.abrir(nombre) if es_atv() else abrir_app_vidaa(nombre)


def encender_wol():
    t = tv_actual()
    mac = (t or {}).get("mac", "")
    if not mac:
        return False
    paquete = b"\xff" * 6 + bytes.fromhex(mac.replace(":", "").replace("-", "")) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for destino in ("255.255.255.255", t["ip"].rsplit(".", 1)[0] + ".255"):
            s.sendto(paquete, (destino, 9))
    return True


busqueda = {"activa": False, "mensaje": ""}


def buscar_en_segundo_plano():
    if busqueda["activa"]:
        return
    busqueda.update(activa=True, mensaje="Buscando TVs…")

    def trabajo():
        try:
            halladas = buscar_tvs()
            agregar_halladas(halladas)
            busqueda["mensaje"] = (f"Encontré {len(halladas)} TV" + ("s" if len(halladas) != 1 else "")
                                   if halladas else "No encontré TVs prendidas en este WiFi")
            if not tv_actual() and len(halladas) == 1:
                elegir_tv(halladas[0][0])
        finally:
            busqueda["activa"] = False

    threading.Thread(target=trabajo, daemon=True).start()


def estado_completo():
    t = tv_actual()
    a = es_atv()
    base = {
        "tvs": cfg["tvs"], "ip": (t or {}).get("ip", ""), "nombreTv": (t or {}).get("nombre", ""),
        "tipo": "atv" if a else "vidaa", "buscando": busqueda["activa"], "mensajeBusqueda": busqueda["mensaje"],
        "escribiendo": escritura["activa"], "escritoHecho": escritura["hecho"], "escritoTotal": escritura["total"],
        "mensajeEscritura": escritura["mensaje"],
    }
    if a:
        base.update(conectado=atv.conectado, pedirCodigo=atv.pedir_codigo, mensaje=atv.mensaje,
                    fuente=None, fuentes=FUENTES_ATV)
    else:
        base.update(conectado=estado["conectado"], pedirCodigo=False, mensaje="",
                    fuente=estado["fuente"], fuentes=estado["fuentes"])
    return base


PAGINA = (Path(__file__).parent / "control.html").read_text(encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/estado":
            return self._json(estado_completo())
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
        p = self.path
        if p == "/tecla" and str(datos.get("tecla", "")).startswith("KEY_"):
            return self._json({"ok": tecla(datos["tecla"])})
        if p == "/escribir":
            return self._json({"ok": escribir(str(datos.get("texto", ""))[:200], bool(datos.get("buscar")))})
        if p == "/detener":
            escritura["detener"] = True
            return self._json({"ok": True})
        if p == "/fuente" and datos.get("id"):
            return self._json({"ok": fuente(str(datos["id"]))})
        if p == "/abrir" and datos.get("app"):
            return self._json({"ok": abrir(str(datos["app"]))})
        if p == "/encender":
            return self._json({"ok": encender_wol()})
        if p == "/buscar":
            buscar_en_segundo_plano()
            return self._json({"ok": True})
        if p == "/elegir" and datos.get("ip"):
            return self._json({"ok": elegir_tv(str(datos["ip"]))})
        if p == "/codigo" and datos.get("codigo") and atv:
            return self._json({"ok": atv.codigo(str(datos["codigo"]))})
        if p == "/cancelarCodigo" and atv:
            atv.cancelar()
            return self._json({"ok": True})
        if p == "/renombrar" and datos.get("ip") and str(datos.get("nombre", "")).strip():
            with lista_lock:
                for t in cfg["tvs"]:
                    if t["ip"] == datos["ip"]:
                        t["nombre"] = str(datos["nombre"]).strip()[:30]
                guardar_config()
            return self._json({"ok": True})
        self._json({"ok": False}, 404)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    t = tv_actual()
    if t:
        elegir_tv(t["ip"])
    else:
        print("No hay TV elegida: usa 'Buscar TVs' en la página.")
    print(f"Control de TV listo. Abre en tu celular:  http://{red_local()}:{PUERTO_WEB}")
    print("Para cerrar: Ctrl+C")
    ThreadingHTTPServer(("0.0.0.0", PUERTO_WEB), Handler).serve_forever()
