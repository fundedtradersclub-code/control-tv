package ec.controltv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
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
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements MqttTv.Listener {

    private static final String CLIENT_ID = "controlcel";
    // Valores locales generados al compilar desde ../config.json (vacios si no existe)
    private static final String IP_INICIAL = Config.TV_IP;
    private static final String MAC_INICIAL = Config.TV_MAC;

    private final String tKey = "/remoteapp/tv/remote_service/" + CLIENT_ID + "$normal/actions/sendkey";
    private final String tSrc = "/remoteapp/tv/ui_service/" + CLIENT_ID + "$normal/actions/changesource";
    private final String tSrcList = "/remoteapp/tv/ui_service/" + CLIENT_ID + "$normal/actions/sourcelist";

    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final MqttTv mqtt = new MqttTv(CLIENT_ID, this);
    private SharedPreferences prefs;
    private WebView web;

    private volatile String fuente = "";
    private volatile String fuentesJson = "[]";
    private volatile String mensaje = "Conectando…";
    private volatile boolean buscando = false;

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
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.addJavascriptInterface(new Puente(), "TV");
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
    private String mac() { return prefs.getString("mac", MAC_INICIAL); }

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
            mensaje = "";
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Recorre la red WiFi buscando el puerto de control de la TV. */
    private void buscarTv() {
        buscando = true;
        mensaje = "Buscando la TV en tu WiFi…";
        try {
            String base = baseRed();
            if (base == null) { mensaje = "Conecta el celular al WiFi de la casa"; return; }
            final AtomicReference<String> hallada = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(48);
            for (int i = 1; i < 255; i++) {
                final String cand = base + i;
                pool.execute(() -> {
                    if (hallada.get() != null) return;
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(cand, MqttTv.PUERTO), 400);
                        hallada.compareAndSet(null, cand);
                    } catch (IOException ignored) { }
                });
            }
            pool.shutdown();
            try { pool.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            String ip = hallada.get();
            if (ip != null && intentar(ip, 2000)) {
                prefs.edit().putString("ip", ip).apply();
            } else {
                mensaje = "No encontré la TV. ¿Está prendida y en el mismo WiFi?";
            }
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
            if (topic.endsWith("/sourcelist")) {
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
            if (k != null && k.startsWith("KEY_")) enviar(tKey, k);
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
                        .toString();
            } catch (JSONException e) {
                return "{}";
            }
        }
    }
}
