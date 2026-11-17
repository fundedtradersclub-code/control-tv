package ec.controltv;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte texto en pulsaciones de flechas/OK para el teclado en pantalla
 * de YouTube en TV VIDAA (probado en una Hisense/Riviera):
 *
 *   A B C D E F G
 *   H I J K L M N
 *   Ñ O P Q R S T
 *   U V W X Y Z -
 *   [ESPACIO] [BORRAR] [BUSCAR]
 *
 * Reglas medidas en la TV:
 *  - Al abrir la busqueda el cursor esta en la A.
 *  - Subir desde una vocal abre su tilde (Á, É...), asi que nunca se sube desde una vocal.
 *  - ESPACIO esta debajo de U/V, BUSCAR debajo de Y/Z; al subir desde un boton
 *    se vuelve a la letra de donde se bajo.
 */
public final class TecladoYouTube {

    private static final String[] FILAS = {"ABCDEFG", "HIJKLMN", "ÑOPQRST", "UVWXYZ-"};
    private static final String VOCALES = "AEIOU";
    private static final String[] TECLAS = {"KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT"};
    private static final int[][] DIR = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** Posicion actual del cursor (fila*7+columna); -1 = sobre un boton, volviendo a 'desde'. */
    private int pos = 0;
    private int desde = 0;

    /** Supone que el cursor esta en la A (recien abierta la busqueda). */
    public void reiniciar() {
        pos = 0;
    }

    public List<String> escribir(String texto) {
        List<String> out = new ArrayList<>();
        volverDeBoton(out);
        String limpio = Normalizer.normalize(texto.toUpperCase().replace("Ñ", "\u0001"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace("\u0001", "Ñ");
        for (char ch : limpio.toCharArray()) {
            if (ch == ' ') {
                int u = 3 * 7, v = 3 * 7 + 1;
                int destino = ruta(pos, u).size() <= ruta(pos, v).size() ? u : v;
                out.addAll(ruta(pos, destino));
                out.add("KEY_DOWN");
                out.add("KEY_OK");
                out.add("KEY_UP");
                pos = destino;
                continue;
            }
            int destino = indice(ch);
            if (destino < 0) continue;  // caracter no disponible en el teclado
            out.addAll(ruta(pos, destino));
            out.add("KEY_OK");
            pos = destino;
        }
        return out;
    }

    public List<String> buscar() {
        List<String> out = new ArrayList<>();
        volverDeBoton(out);
        int y = 3 * 7 + 4;
        out.addAll(ruta(pos, y));
        out.add("KEY_DOWN");
        out.add("KEY_OK");
        desde = y;
        pos = -1;
        return out;
    }

    /** Cuantos caracteres del texto se pueden escribir. */
    public static boolean soportado(char ch) {
        return ch == ' ' || indice(Character.toUpperCase(ch)) >= 0
                || "ÁÉÍÓÚÜáéíóúü".indexOf(ch) >= 0;
    }

    private void volverDeBoton(List<String> out) {
        if (pos == -1) {
            out.add("KEY_UP");
            pos = desde;
        }
    }

    private static int indice(char ch) {
        for (int r = 0; r < FILAS.length; r++) {
            int c = FILAS[r].indexOf(ch);
            if (c >= 0) return r * 7 + c;
        }
        return -1;
    }

    /** Camino mas corto (BFS) sin subir desde una vocal. */
    static List<String> ruta(int a, int b) {
        Map<Integer, int[]> prev = new HashMap<>();
        prev.put(a, null);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(a);
        while (!q.isEmpty()) {
            int cur = q.poll();
            if (cur == b) break;
            int r = cur / 7, c = cur % 7;
            for (int i = 0; i < 4; i++) {
                if (i == 0 && VOCALES.indexOf(FILAS[r].charAt(c)) >= 0) continue;
                int nr = r + DIR[i][0], nc = c + DIR[i][1];
                if (nr < 0 || nr > 3 || nc < 0 || nc > 6) continue;
                int n = nr * 7 + nc;
                if (prev.containsKey(n)) continue;
                prev.put(n, new int[]{cur, i});
                q.add(n);
            }
        }
        ArrayList<String> pasos = new ArrayList<>();
        for (int cur = b; prev.get(cur) != null; cur = prev.get(cur)[0]) {
            pasos.add(0, TECLAS[prev.get(cur)[1]]);
        }
        return pasos;
    }
}
