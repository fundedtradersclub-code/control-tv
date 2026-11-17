"""Convierte texto en flechas/OK para el teclado en pantalla de YouTube en TV VIDAA.

    A B C D E F G
    H I J K L M N
    Ñ O P Q R S T
    U V W X Y Z -
    [ESPACIO] [BORRAR] [BUSCAR]

Reglas medidas en la TV:
- Al abrir la busqueda el cursor esta en la A.
- Subir desde una vocal abre su tilde (Á, É...), asi que nunca se sube desde una vocal.
- ESPACIO esta debajo de U/V y BUSCAR debajo de Y/Z; al subir desde un boton
  se vuelve a la letra de donde se bajo.
"""
import unicodedata
from collections import deque

FILAS = ["ABCDEFG", "HIJKLMN", "ÑOPQRST", "UVWXYZ-"]
POS = {ch: (r, c) for r, fila in enumerate(FILAS) for c, ch in enumerate(fila)}
VOCALES = set("AEIOU")
MOV = {"KEY_UP": (-1, 0), "KEY_DOWN": (1, 0), "KEY_LEFT": (0, -1), "KEY_RIGHT": (0, 1)}


def ruta(a, b):
    """Camino mas corto de a hasta b sin subir desde una vocal."""
    prev = {a: None}
    q = deque([a])
    while q:
        cur = q.popleft()
        if cur == b:
            break
        for k, (dr, dc) in MOV.items():
            if k == "KEY_UP" and FILAS[cur[0]][cur[1]] in VOCALES:
                continue
            n = (cur[0] + dr, cur[1] + dc)
            if 0 <= n[0] < 4 and 0 <= n[1] < 7 and n not in prev:
                prev[n] = (cur, k)
                q.append(n)
    pasos, cur = [], b
    while prev[cur]:
        cur, k = prev[cur]
        pasos.append(k)
    return pasos[::-1]


def normalizar(ch):
    ch = ch.upper()
    if ch == "Ñ":
        return ch
    return "".join(x for x in unicodedata.normalize("NFD", ch) if unicodedata.category(x) != "Mn")


class Teclado:
    def __init__(self):
        self.pos = (0, 0)       # None = sobre un boton
        self.desde = (0, 0)

    def reiniciar(self):
        """Supone que el cursor esta en la A (recien abierta la busqueda)."""
        self.pos = (0, 0)

    def _volver_de_boton(self, out):
        if self.pos is None:
            out.append("KEY_UP")
            self.pos = self.desde

    def escribir(self, texto):
        out = []
        self._volver_de_boton(out)
        for ch in texto:
            if ch == " ":
                destino = min([(3, 0), (3, 1)], key=lambda d: len(ruta(self.pos, d)))
                out += ruta(self.pos, destino) + ["KEY_DOWN", "KEY_OK", "KEY_UP"]
                self.pos = destino
                continue
            ch = normalizar(ch)
            if ch not in POS:
                continue
            out += ruta(self.pos, POS[ch]) + ["KEY_OK"]
            self.pos = POS[ch]
        return out

    def buscar(self):
        out = []
        self._volver_de_boton(out)
        out += ruta(self.pos, (3, 4)) + ["KEY_DOWN", "KEY_OK"]
        self.desde, self.pos = (3, 4), None
        return out
