package ec.controltv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends Activity implements MqttTv.Listener {

    private static final String CLIENT_ID = "controlcel";
    // Valores locales generados al compilar desde ../config.json (vacios si no existe)
    private static final String IP_INICIAL = Config.TV_IP;
    private static final String MAC_INICIAL = Config.TV_MAC;

    private final String tKey = "/remoteapp/tv/remote_service/" + CLIENT_ID + "$normal/actions/sendkey";
    private final String tSrc = "/remoteapp/tv/ui_service/" + CLIENT_ID + "$normal/actions/changesource";
    private final String tSrcList = "/remoteapp/tv/ui_service/" + CLIENT_ID + "$normal/actions/sourcelist";
    private final String tAppList = "/remoteapp/tv/ui_service/" + CLIENT_ID + "$normal/actions/applist";
    private final String tLaunch = "/remoteapp/tv/ui_service/" + CLIENT_ID + "$normal/actions/launchapp";
    private volatile String appsJson = "[]";

    // ---- voz ----
    private static final int PEDIR_VOZ = 41;

    private void escucharVoz() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-419");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di una orden para la TV");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        try {
            startActivityForResult(i, PEDIR_VOZ);
        } catch (ActivityNotFoundException e) {
            web.evaluateJavascript("alVoz(null)", null);
        }
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != PEDIR_VOZ) return;
        ArrayList<String> textos = (resultado == RESULT_OK && datos != null)
                ? datos.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        JSONArray arr = new JSONArray();
        if (textos != null) for (String t : textos) arr.put(t);
        web.evaluateJavascript("alVoz(" + arr + ")", null);
    }

    /** Abre una app de la TV por su nombre (Netflix, YouTube...). */
    private boolean abrirApp(String nombre) {
        try {
            JSONArray apps = new JSONArray(appsJson);
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                if (a.optBoolean("isunInstalled")) continue;
                if (a.optString("name").equalsIgnoreCase(nombre)) {
                    JSONObject p = new JSONObject()
                            .put("appId", a.optString("appId", ""))
                            .put("name", a.optString("name"))
                            .put("url", a.optString("url"))
                            .put("urlType", a.optInt("urlType"))
                            .put("storeType", a.optInt("storeType"));
                    enviar(tLaunch, p.toString());
                    return true;
                }
            }
        } catch (JSONException ignored) { }
        return false;
    }

    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final MqttTv mqtt = new MqttTv(CLIENT_ID, this);
    private SharedPreferences prefs;
    private WebView web;

    private volatile String fuente = "";
    private volatile String fuentesJson = "[]";
    private volatile String mensaje = "Conectando…";
    private volatile boolean buscando = false;

    // Teclado: escribe moviendo el cursor por el teclado en pantalla de YouTube
    private static final int PAUSA_TECLA_MS = 500;
    private final TecladoYouTube teclado = new TecladoYouTube();
    private volatile Thread escritor;
    private volatile int escritoHecho = 0, escritoTotal = 0;

    private synchronized void escribirEnTv(String texto, boolean conBuscar) {
        if (escritor != null) return;
        final List<String> seq = new ArrayList<>(teclado.escribir(texto));
        if (conBuscar) seq.addAll(teclado.buscar());
        if (seq.isEmpty()) return;
        escritoHecho = 0;
        escritoTotal = seq.size();
        Thread t = new Thread(() -> {
            try {
                for (String k : seq) {
                    if (Thread.currentThread().isInterrupted()) { teclado.reiniciar(); break; }
                    hilo.submit(() -> {
                        if (asegurarConexion()) {
                            try { mqtt.publicar(tKey, k); } catch (IOException ignored) { }
                        }
                    }).get();
                    escritoHecho++;
                    Thread.sleep(PAUSA_TECLA_MS);
                }
            } catch (Exception ignored) {
                teclado.reiniciar();  // se detuvo a medias: la posicion ya no es segura
            } finally {
                escritor = null;
            }
        }, "escritor");
        escritor = t;
        t.start();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.parseColor("#16181D"));
        getWindow().setNavigationBarColor(Color.parseColor("#16181D"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#16181D"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);   // para recordar flechas/joystick
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.setWebChromeClient(new WebChromeClient());   // habilita prompt()/confirm() para renombrar TVs
        web.addJavascriptInterface(new Puente(), "TV");
        iniciarLista();
        web.loadUrl("file:///android_asset/control.html");
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hilo.execute(this::asegurarConexion);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hilo.execute(mqtt::cerrar);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    // ---- conexion ----

    private String ip() { return prefs.getString("ip", IP_INICIAL); }

    // ---- varias TVs: lista guardada como JSON [{ip, nombre, mac}] ----

    private volatile boolean pedirEleccion = false;

    private synchronized JSONArray tvs() {
        try {
            return new JSONArray(prefs.getString("tvs", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private synchronized void guardarTvs(JSONArray a) {
        prefs.edit().putString("tvs", a.toString()).apply();
    }

    private JSONObject tvPorIp(JSONArray a, String ip) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && ip.equals(o.optString("ip"))) return o;
        }
        return null;
    }

    /** Primera vez: la TV configurada al compilar entra a la lista. */
    private synchronized void iniciarLista() {
        JSONArray a = tvs();
        if (a.length() == 0 && !ip().isEmpty()) {
            try {
                a.put(new JSONObject().put("ip", ip()).put("nombre", Config.TV_NOMBRE).put("mac", MAC_INICIAL));
                guardarTvs(a);
            } catch (JSONException ignored) { }
        }
    }

    /** Agrega a la lista las TVs nuevas encontradas. */
    private synchronized void agregarNuevas(List<String> ips) {
        JSONArray a = tvs();
        try {
            for (String ip : ips) {
                if (tvPorIp(a, ip) == null) {
                    a.put(new JSONObject().put("ip", ip).put("nombre", "TV " + ip.substring(ip.lastIndexOf('.') + 1)).put("mac", ""));
                }
            }
        } catch (JSONException ignored) { }
        guardarTvs(a);
    }

    /** La TV actual cambio de direccion: se conserva su nombre y su MAC. */
    private synchronized void moverTvActual(String nueva) {
        JSONArray a = tvs();
        JSONObject actual = tvPorIp(a, ip());
        JSONObject destino = tvPorIp(a, nueva);
        try {
            if (actual != null && destino != null && actual != destino) {
                // la nueva direccion se agrego como "TV xx": fusionar con la actual
                destino.put("nombre", actual.optString("nombre")).put("mac", actual.optString("mac"));
                JSONArray b = new JSONArray();
                for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != actual) b.put(a.optJSONObject(i));
                a = b;
            } else if (actual != null && destino == null) {
                actual.put("ip", nueva);
            } else if (destino == null) {
                a.put(new JSONObject().put("ip", nueva)
                        .put("nombre", "TV " + nueva.substring(nueva.lastIndexOf('.') + 1)).put("mac", ""));
            }
        } catch (JSONException ignored) { }
        guardarTvs(a);
        prefs.edit().putString("ip", nueva).apply();
    }

    private String nombreActual() {
        JSONObject o = tvPorIp(tvs(), ip());
        return o != null ? o.optString("nombre", "TV") : "";
    }

    private String mac() {
        JSONObject o = tvPorIp(tvs(), ip());
        return o != null ? o.optString("mac", "") : "";
    }

    private void elegirTv(String nueva) {
        hilo.execute(() -> {
            mqtt.cerrar();
            prefs.edit().putString("ip", nueva).apply();
            fuente = "";
            fuentesJson = "[]";
            teclado.reiniciar();
            pedirEleccion = false;
            mensaje = "Conectando…";
            if (!intentar(nueva, 2000)) mensaje = "Esa TV no responde. ¿Está prendida?";
        });
    }

    /**
     * Se ejecuta siempre en el hilo de red. Si la TV no responde en la direccion
     * guardada (por ejemplo, porque se cambio el router), la busca sola en el WiFi
     * actual y guarda la nueva direccion. Para no gastar bateria cuando la TV esta
     * apagada, la busqueda completa se repite como maximo una vez por minuto.
     */
    private boolean asegurarConexion() {
        ultimoIntento = System.currentTimeMillis();
        if (mqtt.estaConectado()) return true;
        if (!ip().isEmpty() && intentar(ip(), 1500)) return true;
        if (System.currentTimeMillis() - ultimaBusqueda > 60000) {
            ultimaBusqueda = System.currentTimeMillis();
            buscarTv();
            if (mqtt.estaConectado()) return true;
        }
        mensaje = "TV apagada o sin red";
        return false;
    }

    private volatile long ultimaBusqueda = 0;
    private volatile long ultimoIntento = 0;

    private boolean intentar(String ip, int timeout) {
        try {
            mqtt.conectar(ip, timeout);
            mqtt.suscribir("/remoteapp/mobile/#");
            mqtt.publicar(tSrcList, "");
            mqtt.publicar(tAppList, "");
            mensaje = "";
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Recorre la red WiFi y devuelve todas las IP con el puerto de control abierto (null = sin WiFi). */
    private static List<String> escanear() {
        String base = baseRed();
        if (base == null) return null;
        final ConcurrentLinkedQueue<String> halladas = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String cand = base + i;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(cand, MqttTv.PUERTO), 400);
                    halladas.add(cand);
                } catch (IOException ignored) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        List<String> lista = new ArrayList<>(halladas);
        Collections.sort(lista, (x, y) -> Integer.compare(
                Integer.parseInt(x.substring(x.lastIndexOf('.') + 1)),
                Integer.parseInt(y.substring(y.lastIndexOf('.') + 1))));
        return lista;
    }

    /**
     * Busqueda automatica cuando la TV elegida no responde. Si hay una sola TV en
     * la red, es la misma que cambio de direccion. Si hay varias, no adivina: pide elegir.
     */
    private void buscarTv() {
        buscando = true;
        mensaje = "Buscando la TV en tu WiFi…";
        try {
            List<String> ips = escanear();
            if (ips == null) { mensaje = "Conecta el celular al WiFi de la casa"; return; }
            if (ips.isEmpty()) { mensaje = "No encontré la TV. ¿Está prendida y en el mismo WiFi?"; return; }
            if (ips.size() == 1) {
                moverTvActual(ips.get(0));
                if (!intentar(ips.get(0), 2000)) mensaje = "La TV no responde";
                return;
            }
            agregarNuevas(ips);
            if (ips.contains(ip()) && intentar(ip(), 2000)) return;
            pedirEleccion = true;
            mensaje = "Encontré " + ips.size() + " TVs: elige cuál controlar";
        } finally {
            buscando = false;
        }
    }

    /** Busqueda pedida desde la lista de TVs: solo agrega las nuevas. */
    private void buscarTodas() {
        buscando = true;
        try {
            List<String> ips = escanear();
            if (ips == null) { mensaje = "Conecta el celular al WiFi de la casa"; return; }
            agregarNuevas(ips);
            if (ip().isEmpty() && ips.size() == 1) elegirTv(ips.get(0));
            mensaje = ips.isEmpty() ? "No encontré TVs prendidas en este WiFi"
                    : "Encontré " + ips.size() + (ips.size() == 1 ? " TV" : " TVs");
        } finally {
            buscando = false;
        }
    }

    private static String baseRed() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String h = a.getHostAddress();
                        return h.substring(0, h.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void enviar(String topic, String payload) {
        hilo.execute(() -> {
            if (!asegurarConexion()) return;
            try {
                mqtt.publicar(topic, payload);
            } catch (IOException e) {
                mqtt.cerrar();
                if (intentar(ip(), 1500)) {
                    try { mqtt.publicar(topic, payload); } catch (IOException ignored) { }
                }
            }
        });
    }

    private void encenderWol() {
        if (mac().isEmpty()) {
            mensaje = "Encendido por red no configurado; usa el botón de la TV";
            return;
        }
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                String[] partes = mac().split("[:-]");
                byte[] macB = new byte[6];
                for (int i = 0; i < 6; i++) macB[i] = (byte) Integer.parseInt(partes[i], 16);
                byte[] p = new byte[102];
                for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
                for (int i = 6; i < 102; i += 6) System.arraycopy(macB, 0, p, i, 6);
                String base = baseRed();
                String[] destinos = base != null
                        ? new String[]{"255.255.255.255", base + "255"}
                        : new String[]{"255.255.255.255"};
                for (int r = 0; r < 3; r++) {
                    for (String d : destinos) {
                        s.send(new DatagramPacket(p, p.length, InetAddress.getByName(d), 9));
                    }
                    Thread.sleep(200);
                }
            } catch (Exception ignored) { }
            mensaje = "Señal de encendido enviada…";
            try { Thread.sleep(6000); } catch (InterruptedException ignored) { }
            hilo.execute(this::asegurarConexion);
        }).start();
    }

    // ---- mensajes de la TV ----

    @Override
    public void onMensaje(String topic, String payload) {
        try {
            if (topic.endsWith("/applist")) {
                appsJson = new JSONArray(payload).toString();
            } else if (topic.endsWith("/sourcelist")) {
                JSONArray in = new JSONArray(payload);
                JSONArray outArr = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject s = in.getJSONObject(i);
                    String nombre = s.optString("displayname", "");
                    if (nombre.isEmpty()) nombre = s.optString("sourcename", "");
                    outArr.put(new JSONObject().put("id", s.optString("sourceid")).put("nombre", nombre));
                }
                fuentesJson = outArr.toString();
            } else if (topic.endsWith("ui_service/state")) {
                JSONObject o = new JSONObject(payload);
                if ("sourceswitch".equals(o.optString("statetype"))) {
                    fuente = o.optString("displayname", o.optString("sourcename", ""));
                }
            }
        } catch (JSONException ignored) { }
    }

    @Override
    public void onDesconectado() {
        mensaje = "TV apagada o sin red";
    }

    // ---- puente con la pagina ----

    private class Puente {
        @JavascriptInterface
        public void tecla(String k) {
            if (k != null && k.startsWith("KEY_")) {
                // el cursor se movio a mano: para el teclado, volver a suponer la A
                teclado.reiniciar();
                enviar(tKey, k);
            }
        }

        @JavascriptInterface
        public void escribir(String texto, boolean conBuscar) {
            if (texto != null) escribirEnTv(texto, conBuscar);
        }

        @JavascriptInterface
        public void soloBuscar() {
            escribirEnTv("", true);
        }

        @JavascriptInterface
        public void detenerEscritura() {
            Thread t = escritor;
            if (t != null) t.interrupt();
        }

        @JavascriptInterface
        public void fuente(String id) {
            try {
                enviar(tSrc, new JSONObject().put("sourceid", id).toString());
            } catch (JSONException ignored) { }
        }

        @JavascriptInterface
        public void encender() {
            encenderWol();
        }

        @JavascriptInterface
        public void buscar() {
            if (!buscando) hilo.execute(() -> { mqtt.cerrar(); buscarTv(); });
        }

        @JavascriptInterface
        public void escuchar() {
            runOnUiThread(MainActivity.this::escucharVoz);
        }

        @JavascriptInterface
        public boolean abrir(String nombre) {
            return nombre != null && abrirApp(nombre);
        }

        @JavascriptInterface
        public String apps() {
            return appsJson;
        }

        @JavascriptInterface
        public void buscarTodas() {
            if (!buscando) new Thread(MainActivity.this::buscarTodas).start();
        }

        @JavascriptInterface
        public void elegir(String ipTv) {
            if (ipTv != null && tvPorIp(tvs(), ipTv) != null) elegirTv(ipTv);
        }

        @JavascriptInterface
        public void renombrar(String ipTv, String nombre) {
            if (ipTv == null || nombre == null || nombre.trim().isEmpty()) return;
            synchronized (MainActivity.this) {
                JSONArray a = tvs();
                JSONObject o = tvPorIp(a, ipTv);
                if (o == null) return;
                try { o.put("nombre", nombre.trim().substring(0, Math.min(30, nombre.trim().length()))); } catch (JSONException ignored) { }
                guardarTvs(a);
            }
        }

        @JavascriptInterface
        public void olvidar(String ipTv) {
            synchronized (MainActivity.this) {
                JSONArray a = tvs(), b = new JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && !o.optString("ip").equals(ipTv)) b.put(o);
                }
                guardarTvs(b);
            }
            if (ipTv != null && ipTv.equals(ip())) {
                hilo.execute(() -> {
                    mqtt.cerrar();
                    prefs.edit().putString("ip", "").apply();
                    fuentesJson = "[]";
                    fuente = "";
                    mensaje = "Elige una TV";
                });
            }
        }

        @JavascriptInterface
        public String estado() {
            // Mientras la app esta abierta y sin conexion, reintenta cada 10 s
            if (!mqtt.estaConectado() && !buscando
                    && System.currentTimeMillis() - ultimoIntento > 10000) {
                ultimoIntento = System.currentTimeMillis();
                hilo.execute(MainActivity.this::asegurarConexion);
            }
            try {
                return new JSONObject()
                        .put("conectado", mqtt.estaConectado())
                        .put("fuente", fuente)
                        .put("fuentes", new JSONArray(fuentesJson))
                        .put("mensaje", mensaje)
                        .put("buscando", buscando)
                        .put("ip", ip())
                        .put("nombreTv", nombreActual())
                        .put("tvs", tvs())
                        .put("elegir", pedirEleccion)
                        .put("escribiendo", escritor != null)
                        .put("escritoHecho", escritoHecho)
                        .put("escritoTotal", escritoTotal)
                        .toString();
            } catch (JSONException e) {
                return "{}";
            }
        }
    }
}
