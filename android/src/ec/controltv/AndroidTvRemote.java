package ec.controltv;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Control remoto de TVs Android TV / Google TV con el protocolo oficial
 * "Android TV Remote v2" (puertos 6467 para vincular y 6466 para controlar).
 * Java puro, sin librerias: TLS con certificado propio, protobuf a mano.
 *
 * Basado en la documentacion del protocolo y en la libreria abierta androidtvremote2.
 */
public class AndroidTvRemote {

    public static final int PUERTO_CONTROL = 6466;
    public static final int PUERTO_VINCULAR = 6467;

    // Funciones pedidas a la TV: PING | KEY | IME | POWER | VOLUME | APP_LINK
    private static final int FUNCIONES = 1 | 2 | 4 | 32 | 64 | 512;

    public interface Listener {
        void onDesconectado();
        void onAppActual(String paquete);
    }

    // ===================== certificado del cliente =====================

    /** Certificado + clave del control, guardados como texto (base64) para reusarlos. */
    public static final class Identidad {
        final PrivateKey clave;
        final X509Certificate cert;

        Identidad(PrivateKey clave, X509Certificate cert) {
            this.clave = clave;
            this.cert = cert;
        }

        public String guardar() throws Exception {
            Base64.Encoder b = Base64.getEncoder();
            return b.encodeToString(clave.getEncoded()) + "." + b.encodeToString(cert.getEncoded());
        }

        public static Identidad cargar(String s) throws Exception {
            String[] p = s.split("\\.");
            Base64.Decoder d = Base64.getDecoder();
            PrivateKey k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(d.decode(p[0])));
            X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(d.decode(p[1])));
            return new Identidad(k, c);
        }

        /** Crea un certificado autofirmado RSA 2048 (DER armado a mano). */
        public static Identidad crear() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();

            byte[] algSha256Rsa = seq(oid(new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}), new byte[]{0x05, 0x00});
            byte[] nombre = seq(tlv(0x31, seq(oid(new byte[]{0x55, 0x04, 0x03}), tlv(0x0C, "atvremote".getBytes(StandardCharsets.UTF_8)))));
            SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long ahora = System.currentTimeMillis();
            byte[] validez = seq(tlv(0x17, f.format(new Date(ahora - 86400000L)).getBytes(StandardCharsets.US_ASCII)),
                    tlv(0x17, f.format(new Date(ahora + 20L * 365 * 86400000L)).getBytes(StandardCharsets.US_ASCII)));
            byte[] serie = new byte[9];
            new SecureRandom().nextBytes(serie);
            serie[0] = 0;  // positivo
            byte[] tbs = seq(
                    tlv(0xA0, tlv(0x02, new byte[]{2})),   // version v3
                    tlv(0x02, serie),
                    algSha256Rsa,
                    nombre,
                    validez,
                    nombre,
                    kp.getPublic().getEncoded());          // SubjectPublicKeyInfo
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(kp.getPrivate());
            s.update(tbs);
            byte[] firma = s.sign();
            byte[] bits = new byte[firma.length + 1];
            System.arraycopy(firma, 0, bits, 1, firma.length);
            byte[] der = seq(tbs, algSha256Rsa, tlv(0x03, bits));
            X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            return new Identidad(kp.getPrivate(), c);
        }

        private static byte[] oid(byte[] v) { return tlv(0x06, v); }

        private static byte[] seq(byte[]... partes) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            for (byte[] p : partes) o.write(p, 0, p.length);
            return tlv(0x30, o.toByteArray());
        }

        private static byte[] tlv(int tag, byte[] v) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(tag);
            int n = v.length;
            if (n < 128) {
                o.write(n);
            } else if (n < 256) {
                o.write(0x81); o.write(n);
            } else {
                o.write(0x82); o.write(n >> 8); o.write(n & 0xFF);
            }
            o.write(v, 0, v.length);
            return o.toByteArray();
        }
    }

    private static SSLSocket abrirTls(String ip, int puerto, Identidad id, int timeoutMs) throws Exception {
        char[] pass = "x".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("c", id.clave, pass, new Certificate[]{id.cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        // La TV usa un certificado autofirmado: se acepta (la seguridad la da la vinculacion)
        TrustManager[] confiar = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), confiar, new SecureRandom());
        java.net.Socket base = new java.net.Socket();
        base.connect(new InetSocketAddress(ip, puerto), timeoutMs);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(base, ip, puerto, true);
        s.setSoTimeout(timeoutMs);
        s.startHandshake();
        return s;
    }

    // ===================== vinculacion (puerto 6467) =====================

    /** Sesion de vinculacion: iniciar() hace aparecer el codigo en la TV; terminar(codigo) lo confirma. */
    public static final class Vinculacion {
        private final SSLSocket s;
        private final Identidad id;

        public Vinculacion(String ip, Identidad id) throws Exception {
            this.id = id;
            this.s = abrirTls(ip, PUERTO_VINCULAR, id, 10000);
        }

        private static Pb externo() {
            return new Pb().varint(1, 2).varint(2, 200);   // protocol_version=2, status=OK
        }

        private Map<Integer, List<Object>> esperar(int campo) throws IOException {
            Map<Integer, List<Object>> m = Pb.leer(leerMensaje(s.getInputStream()));
            long estado = Pb.num(m, 2, 200);
            if (estado != 200) throw new IOException("La TV respondio con estado " + estado);
            if (!m.containsKey(campo)) throw new IOException("Respuesta inesperada de la TV");
            return m;
        }

        public void iniciar(String nombreCliente) throws Exception {
            OutputStream out = s.getOutputStream();
            escribirMensaje(out, externo().msg(10, new Pb().texto(1, "atvremote").texto(2, nombreCliente)));
            esperar(11);   // pairing_request_ack
            Pb enc = new Pb().varint(1, 3).varint(2, 6);   // hexadecimal, 6 simbolos
            escribirMensaje(out, externo().msg(20, new Pb().msg(1, enc).varint(3, 1)));   // options, rol INPUT
            esperar(20);   // options
            escribirMensaje(out, externo().msg(30, new Pb().msg(1, enc).varint(2, 1)));   // configuration
            esperar(31);   // configuration_ack: la TV ya muestra el codigo
        }

        /** Confirma el codigo de 6 caracteres que muestra la TV. */
        public void terminar(String codigo) throws Exception {
            codigo = codigo.trim().toUpperCase(Locale.US);
            if (!codigo.matches("[0-9A-F]{6}")) throw new IllegalArgumentException("El código tiene 6 caracteres (0-9 y A-F)");
            RSAPublicKey cli = (RSAPublicKey) id.cert.getPublicKey();
            RSAPublicKey tv = (RSAPublicKey) s.getSession().getPeerCertificates()[0].getPublicKey();
            MessageDigest h = MessageDigest.getInstance("SHA-256");
            h.update(sinSigno(cli.getModulus()));
            h.update(sinSigno(cli.getPublicExponent()));
            h.update(sinSigno(tv.getModulus()));
            h.update(sinSigno(tv.getPublicExponent()));
            h.update(hex(codigo.substring(2)));
            byte[] secreto = h.digest();
            if ((secreto[0] & 0xFF) != Integer.parseInt(codigo.substring(0, 2), 16)) {
                throw new IllegalArgumentException("Código incorrecto");
            }
            escribirMensaje(s.getOutputStream(), externo().msg(40, new Pb().bytes(1, secreto)));
            esperar(41);   // secret_ack
            cerrar();
        }

        public void cerrar() {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ===================== control (puerto 6466) =====================

    private final Identidad id;
    private final Listener listener;
    private SSLSocket socket;
    private volatile boolean conectado;
    private volatile boolean listo;
    private volatile long ultimoRecibido;
    private int imeCounter, campoCounter;
    private volatile String appActual = "";
    private volatile int volumen = -1;

    public int volumen() { return volumen; }

    public AndroidTvRemote(Identidad id, Listener listener) {
        this.id = id;
        this.listener = listener;
    }

    public boolean estaConectado() { return conectado; }
    public String appActual() { return appActual; }
    public long msDesdeUltimoRecibido() { return System.currentTimeMillis() - ultimoRecibido; }

    /**
     * Conecta y espera a que la TV quede lista. Si la TV no reconoce el certificado
     * (no esta vinculada) lanza NoVinculada.
     */
    public synchronized void conectar(String ip, int timeoutMs) throws Exception {
        cerrar();
        SSLSocket s;
        try {
            s = abrirTls(ip, PUERTO_CONTROL, id, timeoutMs);
        } catch (javax.net.ssl.SSLException e) {
            throw new NoVinculada();
        }
        s.setSoTimeout(0);
        socket = s;
        conectado = true;
        listo = false;
        ultimoRecibido = System.currentTimeMillis();
        final SSLSocket propio = s;
        Thread t = new Thread(() -> leerBucle(propio), "atv-lector");
        t.setDaemon(true);
        t.start();
        long fin = System.currentTimeMillis() + timeoutMs;
        while (!listo && conectado && System.currentTimeMillis() < fin) {
            wait(100);
        }
        if (!conectado) throw new NoVinculada();
        if (!listo) { cerrar(); throw new IOException("La TV no termino de conectar"); }
    }

    public static final class NoVinculada extends IOException {
        NoVinculada() { super("La TV no esta vinculada con este control"); }
    }

    public synchronized void cerrar() {
        conectado = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }
    }

    /** Pulsacion corta de una tecla (codigos KEYCODE_* de Android). */
    public void tecla(int keycode) throws IOException {
        enviar(new Pb().msg(10, new Pb().varint(1, keycode).varint(2, 3)));
    }

    /** Escribe texto directo en el campo de texto activo de la TV. */
    public void texto(String t) throws IOException {
        Pb objeto = new Pb().varint(1, t.length()).varint(2, t.length()).texto(3, t);
        Pb edicion = new Pb().varint(1, 1).msg(2, objeto);
        int ime, campo;
        synchronized (this) { ime = imeCounter; campo = campoCounter; }
        enviar(new Pb().msg(21, new Pb().varint(1, ime).varint(2, campo).msg(3, edicion)));
    }

    /** Abre una app por enlace (ej. "https://www.netflix.com/title"). */
    public void abrirEnlace(String enlace) throws IOException {
        enviar(new Pb().msg(90, new Pb().texto(1, enlace)));
    }

    private synchronized void enviar(Pb m) throws IOException {
        if (!conectado || socket == null) throw new IOException("Sin conexion");
        try {
            escribirMensaje(socket.getOutputStream(), m);
        } catch (IOException e) {
            cerrar();
            throw e;
        }
    }

    private void leerBucle(SSLSocket s) {
        try {
            InputStream in = s.getInputStream();
            while (true) {
                byte[] crudo = leerMensaje(in);
                ultimoRecibido = System.currentTimeMillis();
                manejar(Pb.leer(crudo));
            }
        } catch (Exception e) {
            boolean eraActual;
            synchronized (this) {
                eraActual = (s == socket);
                if (eraActual) conectado = false;
                notifyAll();
            }
            if (eraActual && listo) listener.onDesconectado();
        }
    }

    private void manejar(Map<Integer, List<Object>> m) throws IOException {
        if (m.containsKey(1)) {                       // remote_configure
            Map<Integer, List<Object>> cfg = Pb.leer((byte[]) m.get(1).get(0));
            int soportadas = (int) Pb.num(cfg, 1, FUNCIONES);
            Pb info = new Pb().varint(3, 1).texto(4, "1").texto(5, "atvremote").texto(6, "1.0.0");
            enviar(new Pb().msg(1, new Pb().varint(1, FUNCIONES & soportadas).msg(2, info)));
        } else if (m.containsKey(2)) {                // remote_set_active
            enviar(new Pb().msg(2, new Pb().varint(1, FUNCIONES)));
        } else if (m.containsKey(8)) {                // remote_ping_request
            Map<Integer, List<Object>> p = Pb.leer((byte[]) m.get(8).get(0));
            enviar(new Pb().msg(9, new Pb().varint(1, Pb.num(p, 1, 0))));
        } else if (m.containsKey(40)) {               // remote_start: lista para recibir ordenes
            synchronized (this) { listo = true; notifyAll(); }
        } else if (m.containsKey(20)) {               // remote_ime_key_inject: app y contadores del teclado
            Map<Integer, List<Object>> k = Pb.leer((byte[]) m.get(20).get(0));
            if (k.containsKey(1)) {
                Map<Integer, List<Object>> app = Pb.leer((byte[]) k.get(1).get(0));
                synchronized (this) { imeCounter = (int) Pb.num(app, 1, imeCounter); }
                appActual = Pb.texto(app, 12, appActual);
                listener.onAppActual(appActual);
            }
            if (k.containsKey(2)) {
                Map<Integer, List<Object>> campo = Pb.leer((byte[]) k.get(2).get(0));
                synchronized (this) { campoCounter = (int) Pb.num(campo, 1, campoCounter); }
            }
        } else if (m.containsKey(22)) {               // remote_ime_show_request
            Map<Integer, List<Object>> r = Pb.leer((byte[]) m.get(22).get(0));
            if (r.containsKey(2)) {
                Map<Integer, List<Object>> campo = Pb.leer((byte[]) r.get(2).get(0));
                synchronized (this) { campoCounter = (int) Pb.num(campo, 1, campoCounter); }
            }
        } else if (m.containsKey(50)) {               // remote_set_volume_level
            Map<Integer, List<Object>> v = Pb.leer((byte[]) m.get(50).get(0));
            volumen = (int) Pb.num(v, 7, volumen);
        } else if (m.containsKey(21)) {               // remote_ime_batch_edit
            Map<Integer, List<Object>> b = Pb.leer((byte[]) m.get(21).get(0));
            synchronized (this) {
                if (imeCounter == 0) imeCounter = (int) Pb.num(b, 1, 0);
                if (campoCounter == 0) campoCounter = (int) Pb.num(b, 2, 0);
            }
        }
    }

    // ===================== utilidades =====================

    private static byte[] sinSigno(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] c = new byte[b.length - 1];
            System.arraycopy(b, 1, c, 0, c.length);
            return c;
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    /** Mensajes con prefijo de largo (varint) + protobuf. */
    private static void escribirMensaje(OutputStream out, Pb m) throws IOException {
        byte[] cuerpo = m.bytes();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Pb.escribirVarint(o, cuerpo.length);
        o.write(cuerpo, 0, cuerpo.length);
        out.write(o.toByteArray());
        out.flush();
    }

    private static byte[] leerMensaje(InputStream in) throws IOException {
        long largo = 0;
        int corr = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("Conexion cerrada");
            largo |= (long) (b & 0x7F) << corr;
            corr += 7;
        } while ((b & 0x80) != 0);
        if (largo > 1 << 20) throw new IOException("Mensaje demasiado grande");
        byte[] r = new byte[(int) largo];
        new DataInputStream(in).readFully(r);
        return r;
    }

    /** Protobuf minimo: solo varint y bytes/texto/mensajes. */
    static final class Pb {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        Pb varint(int campo, long v) {
            escribirVarint(o, ((long) campo << 3));
            escribirVarint(o, v);
            return this;
        }

        Pb bytes(int campo, byte[] v) {
            escribirVarint(o, ((long) campo << 3) | 2);
            escribirVarint(o, v.length);
            o.write(v, 0, v.length);
            return this;
        }

        Pb texto(int campo, String v) { return bytes(campo, v.getBytes(StandardCharsets.UTF_8)); }
        Pb msg(int campo, Pb v) { return bytes(campo, v.bytes()); }
        byte[] bytes() { return o.toByteArray(); }

        static void escribirVarint(ByteArrayOutputStream o, long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
        }

        static Map<Integer, List<Object>> leer(byte[] b) {
            Map<Integer, List<Object>> m = new HashMap<>();
            int[] p = {0};
            while (p[0] < b.length) {
                long tag = varint(b, p);
                int campo = (int) (tag >>> 3), tipo = (int) (tag & 7);
                Object v;
                if (tipo == 0) {
                    v = varint(b, p);
                } else if (tipo == 2) {
                    int n = (int) varint(b, p);
                    byte[] x = new byte[n];
                    System.arraycopy(b, p[0], x, 0, n);
                    p[0] += n;
                    v = x;
                } else if (tipo == 5) {
                    p[0] += 4; continue;
                } else if (tipo == 1) {
                    p[0] += 8; continue;
                } else {
                    break;
                }
                List<Object> l = m.get(campo);
                if (l == null) { l = new ArrayList<>(); m.put(campo, l); }
                l.add(v);
            }
            return m;
        }

        private static long varint(byte[] b, int[] p) {
            long r = 0;
            int corr = 0;
            while (true) {
                int x = b[p[0]++] & 0xFF;
                r |= (long) (x & 0x7F) << corr;
                if ((x & 0x80) == 0) return r;
                corr += 7;
            }
        }

        static long num(Map<Integer, List<Object>> m, int campo, long def) {
            List<Object> l = m.get(campo);
            return (l != null && l.get(0) instanceof Long) ? (Long) l.get(0) : def;
        }

        static String texto(Map<Integer, List<Object>> m, int campo, String def) {
            List<Object> l = m.get(campo);
            return (l != null && l.get(0) instanceof byte[]) ? new String((byte[]) l.get(0), StandardCharsets.UTF_8) : def;
        }
    }
}
