package ec.controltv;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Cliente MQTT 3.1.1 minimo (solo QoS 0) para el servicio de control remoto
 * de las TV Hisense (puerto 36669). Sin librerias externas.
 */
public class MqttTv {

    public interface Listener {
        void onMensaje(String topic, String payload);
        void onDesconectado();
    }

    public static final int PUERTO = 36669;
    // Usuario/clave publicos del protocolo de control de Hisense
    private static final String USUARIO = "hisenseservice";
    private static final String CLAVE = "multimqttservice";

    private final String clientId;
    private final Listener listener;
    private Socket socket;
    private OutputStream out;
    private volatile boolean conectado;
    private int packetId = 1;
    /** Momento en que llego el ultimo paquete de la TV (cualquiera, incluido PINGRESP). */
    private volatile long ultimoRecibido = 0;

    public long msDesdeUltimoRecibido() {
        return System.currentTimeMillis() - ultimoRecibido;
    }

    /**
     * Pregunta a la TV si sigue ahi (PINGREQ) y espera su respuesta.
     * Si no responde a tiempo, cierra la conexion: era una conexion "zombi".
     */
    public boolean verificar(int esperaMs) {
        if (!conectado) return false;
        long antes = System.currentTimeMillis();
        try {
            synchronized (this) { enviarPaquete(0xC0, new byte[0]); }
        } catch (IOException e) {
            cerrar();
            return false;
        }
        while (System.currentTimeMillis() - antes < esperaMs) {
            if (ultimoRecibido >= antes) return true;
            try { Thread.sleep(40); } catch (InterruptedException e) { return conectado; }
        }
        cerrar();
        return false;
    }

    public MqttTv(String clientId, Listener listener) {
        this.clientId = clientId;
        this.listener = listener;
    }

    public boolean estaConectado() {
        return conectado;
    }

    /** Conecta y arranca el hilo lector. Lanza IOException si falla. */
    public synchronized void conectar(String ip, int timeoutMs) throws IOException {
        cerrar();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, PUERTO), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());

        ByteArrayOutputStream vh = new ByteArrayOutputStream();
        escribirTexto(vh, "MQTT");
        vh.write(4);                 // MQTT 3.1.1
        vh.write(0xC2);              // usuario + clave + clean session
        vh.write(0); vh.write(60);   // keep alive 60 s
        escribirTexto(vh, clientId);
        escribirTexto(vh, USUARIO);
        escribirTexto(vh, CLAVE);
        enviarPaquete(0x10, vh.toByteArray());

        int tipo = in.readUnsignedByte();
        int largo = leerLargo(in);
        byte[] cuerpo = new byte[largo];
        in.readFully(cuerpo);
        if ((tipo >> 4) != 2 || largo < 2 || cuerpo[1] != 0) {
            s.close();
            throw new IOException("La TV rechazo la conexion");
        }
        s.setSoTimeout(0);
        socket = s;
        conectado = true;
        ultimoRecibido = System.currentTimeMillis();

        final Socket propio = s;
        Thread lector = new Thread(() -> leerBucle(propio, in), "mqtt-lector");
        lector.setDaemon(true);
        lector.start();
        Thread ping = new Thread(() -> pingBucle(propio), "mqtt-ping");
        ping.setDaemon(true);
        ping.start();
    }

    public synchronized void suscribir(String filtro) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int id = siguienteId();
        b.write(id >> 8); b.write(id & 0xFF);
        escribirTexto(b, filtro);
        b.write(0);
        enviarPaquete(0x82, b.toByteArray());
    }

    public synchronized void publicar(String topic, String payload) throws IOException {
        if (!conectado) throw new IOException("Sin conexion");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        escribirTexto(b, topic);
        b.write(payload.getBytes(StandardCharsets.UTF_8));
        enviarPaquete(0x30, b.toByteArray());
    }

    public synchronized void cerrar() {
        conectado = false;
        if (socket != null) {
            try { enviarPaquete(0xE0, new byte[0]); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }
    }

    // ---- internos ----

    private void leerBucle(Socket s, DataInputStream in) {
        try {
            while (true) {
                int cab = in.readUnsignedByte();
                int largo = leerLargo(in);
                byte[] cuerpo = new byte[largo];
                in.readFully(cuerpo);
                ultimoRecibido = System.currentTimeMillis();
                if ((cab >> 4) == 3) {  // PUBLISH
                    int lt = ((cuerpo[0] & 0xFF) << 8) | (cuerpo[1] & 0xFF);
                    String topic = new String(cuerpo, 2, lt, StandardCharsets.UTF_8);
                    int ini = 2 + lt + (((cab >> 1) & 3) > 0 ? 2 : 0);
                    String payload = new String(cuerpo, ini, largo - ini, StandardCharsets.UTF_8);
                    listener.onMensaje(topic, payload);
                }
            }
        } catch (IOException | RuntimeException e) {
            boolean eraActual;
            synchronized (this) { eraActual = (s == socket); if (eraActual) conectado = false; }
            if (eraActual) listener.onDesconectado();
        }
    }

    /** Ping cada 15 s; si la TV lleva 40 s sin responder nada, la conexion esta muerta. */
    private void pingBucle(Socket s) {
        try {
            while (true) {
                Thread.sleep(15000);
                synchronized (this) {
                    if (s != socket || !conectado) return;
                    enviarPaquete(0xC0, new byte[0]);
                }
                if (msDesdeUltimoRecibido() > 40000) {
                    boolean eraActual;
                    synchronized (this) { eraActual = (s == socket); }
                    if (eraActual) {
                        cerrar();
                        listener.onDesconectado();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            boolean eraActual;
            synchronized (this) { eraActual = (s == socket); }
            if (eraActual) { cerrar(); listener.onDesconectado(); }
        }
    }

    private int siguienteId() {
        packetId = packetId % 65535 + 1;
        return packetId;
    }

    private void enviarPaquete(int cabecera, byte[] cuerpo) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(cabecera);
        int x = cuerpo.length;
        do {
            int d = x % 128;
            x /= 128;
            if (x > 0) d |= 0x80;
            p.write(d);
        } while (x > 0);
        p.write(cuerpo);
        out.write(p.toByteArray());
        out.flush();
    }

    private static int leerLargo(DataInputStream in) throws IOException {
        int mult = 1, valor = 0, d;
        do {
            d = in.readUnsignedByte();
            valor += (d & 127) * mult;
            mult *= 128;
        } while ((d & 128) != 0);
        return valor;
    }

    private static void escribirTexto(ByteArrayOutputStream b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.write(bytes.length >> 8);
        b.write(bytes.length & 0xFF);
        b.write(bytes, 0, bytes.length);
    }
}
