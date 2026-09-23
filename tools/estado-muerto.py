#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Estado declarado y nunca leido.

POR QUE EXISTE. Cuatro veces en esta app ha aparecido lo mismo: una funcion
escrita entera y sin un solo cable que la conecte.

  1. AUTO_LOCK_MS prometia cerrar la cartera a los dos minutos de inactividad.
     La constante no se leia en ningun sitio y lastInteraction se actualizaba
     en cada toque para nada.
  2. La proteccion termica: getCpuTemp() y getBatteryTemp() definidas y sin una
     llamada, tempCallback invocado sin que nadie se suscribiera, tvThermal en
     GONE y sin texto nunca, y cuatro variables de estado jamas leidas. La app
     corria al 100 % de CPU durante dias sin bajar nunca.
  3. El limite de CPU de Kangaroo: arrancarKangaroo lo calculaba y SOLO LO
     PINTABA. El motor usaba otro.
  4. Los ocho idiomas: Strings.ALL no lo lee nadie y fromSystem() no se llama.

Las cuatro compilaban en verde. Ninguna se ve leyendo el fichero de arriba
abajo, porque lo que falta no esta escrito en ninguna parte.

QUE BUSCA. No "lo que nadie toca" — eso es basura inofensiva. Busca lo que
alguien MANTIENE y nadie consulta, que es la forma que tiene una funcion rota
de esconderse: el flag que se pone y no se mira, la vista que se crea y no se
anade, el contador que sube y no se lee.

DOS TRAMPAS, las dos aprendidas equivocandose con este mismo script:

  - Los comentarios. Una variable mencionada solo en el comentario que explica
    por que se quito cuenta como usada, y entonces el barrido se da por limpio
    justo despues de ensuciarse.
  - Las plantillas de cadena. "$foo" y "${foo.bar}" son LECTURAS. Quitar el
    literal entero, que es lo comodo, daba por muerto cachedPuzzleLabel, que se
    lee en "Puzzle $cachedPuzzleLabel: $etaPuzzle".

Uso:  tools/estado-muerto.py [--estricto]
      --estricto devuelve 1 si encuentra algo. Sin el, solo informa.
"""
import re, io, os, sys, glob

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app", "src", "main", "java", "com", "hunter", "btc")


def limpiar(src):
    """Fuera comentarios y literales, PERO dejando lo que va dentro de ${...}.

    Conserva los saltos de linea para que los numeros sigan cuadrando.
    """
    out, i, n, modo = [], 0, len(src), None
    while i < n:
        c, dos, tres = src[i], src[i:i+2], src[i:i+3]
        if modo is None:
            if dos == '//':  modo = 'linea';  i += 2; continue
            if dos == '/*':  modo = 'bloque'; i += 2; continue
            if tres == '"""': modo = 'cad3';  i += 3; continue
            if c == '"':     modo = 'cad';    i += 1; continue
            out.append(c); i += 1
        elif modo == 'linea':
            if c == '\n': modo = None; out.append(c)
            i += 1
        elif modo == 'bloque':
            if dos == '*/': modo = None; i += 2; continue
            if c == '\n': out.append(c)
            i += 1
        else:
            if modo == 'cad3' and tres == '"""': modo = None; i += 3; continue
            if modo == 'cad':
                if c == '\\': i += 2; continue
                if c == '"': modo = None; i += 1; continue
            if c == '$':
                j = i + 1
                if j < n and src[j] == '{':
                    prof, k = 1, j + 1
                    while k < n and prof:
                        if src[k] == '{': prof += 1
                        elif src[k] == '}': prof -= 1
                        k += 1
                    out.append(' ' + src[j+1:k-1] + ' '); i = k; continue
                m = re.match(r'[A-Za-z_]\w*', src[j:])
                if m:
                    out.append(' ' + m.group(0) + ' '); i = j + m.end(); continue
            if c == '\n': out.append(c)
            i += 1
    return ''.join(out)


# Lo conocido y decidido, con su motivo. NO es una alfombra: cada entrada dice
# por que se queda, y lo que no este aqui hace fallar --estricto. La diferencia
# entre una excepcion y una excusa es que la excepcion esta escrita.
CONOCIDOS = {
    # Vacio. Estuvo ("Strings.kt", "ALL"): el mapa de ocho idiomas que no leia
    # nadie. Se decidio que la app es en ingles y se quitaron las siete tablas.
}

ESCRITURA = re.compile(r'\s*(=[^=]|\+=|-=|\*=|/=|\+\+|--)')
DECL_PRIV = re.compile(
    r'^\s*(?:@\w+\s+)*private\s+(?:@\w+\s+)*(?:lateinit\s+)?(?:const\s+)?'
    r'(val|var)\s+([A-Za-z_]\w*)', re.M)


def privados(ruta, src):
    """Miembros privados sin una sola lectura en su propio fichero.

    Privado en Kotlin no sale del fichero, asi que mirar ahi basta y el
    resultado es exacto, no una aproximacion.
    """
    fuera = []
    for m in DECL_PRIV.finditer(src):
        nombre = m.group(2)
        ln = src[:m.start()].count('\n') + 1
        lecturas = 0
        escrituras = 0
        for u in re.finditer(r'\b%s\b' % re.escape(nombre), src):
            if src[:u.start()].count('\n') + 1 == ln:
                continue                      # la propia declaracion
            if ESCRITURA.match(src[u.end():u.end()+4]):
                escrituras += 1
            else:
                lecturas += 1
        if lecturas == 0:
            fuera.append((ln, m.group(1), nombre, escrituras))
    return fuera


def miembros_de_object(ruta, src, todo):
    """Miembros de object/companion sin lectura en NINGUN fichero.

    Se exige que la sangria sea exactamente la del cuerpo del object: si no,
    entran las locales de cada funcion y el informe se vuelve inutil.
    """
    fuera = []
    for m in re.finditer(r'^(\s*)(?:private\s+)?(?:companion\s+)?object\s+\w*\s*\{',
                         src, re.M):
        sangria = m.group(1) + "    "
        ini, prof, i = m.end(), 1, m.end()
        while i < len(src) and prof:
            if src[i] == '{': prof += 1
            elif src[i] == '}': prof -= 1
            i += 1
        cuerpo, base = src[ini:i], src[:ini].count('\n') + 1
        decl = re.compile(
            r'^%s(?:@\w+\s+)*(?:(?:internal|public)\s+)?(?:lateinit\s+)?'
            r'(?:const\s+)?(val|var)\s+([A-Za-z_]\w*)\s*[:=]' % re.escape(sangria), re.M)
        for d in decl.finditer(cuerpo):
            nombre = d.group(2)
            ln = base + cuerpo[:d.start()].count('\n')
            # La declaracion aparece una vez aqui; se descuenta por posicion.
            pos = todo.find(d.group(0))
            lecturas = 0
            for u in re.finditer(r'\b%s\b' % re.escape(nombre), todo):
                if pos >= 0 and pos <= u.start() < pos + len(d.group(0)):
                    continue
                if ESCRITURA.match(todo[u.end():u.end()+4]):
                    continue
                lecturas += 1
            if lecturas == 0:
                fuera.append((ln, d.group(1), nombre, 0))
    return fuera


def main():
    estricto = "--estricto" in sys.argv
    ficheros = sorted(glob.glob(os.path.join(RAIZ, "**", "*.kt"), recursive=True))
    if not ficheros:
        print("no encuentro las fuentes en %s" % RAIZ); return 1
    limpio = {p: limpiar(io.open(p, encoding='utf-8').read()) for p in ficheros}
    todo = "\n".join(limpio.values())

    filas = []
    for p in ficheros:
        base = os.path.basename(p)
        for ln, tipo, n, esc in privados(p, limpio[p]):
            filas.append((base, ln, tipo, n, esc, "privado"))
        for ln, tipo, n, esc in miembros_de_object(p, limpio[p], todo):
            filas.append((base, ln, tipo, n, esc, "object"))

    conocidas = [f for f in filas if (f[0], f[3]) in CONOCIDOS]
    filas = [f for f in filas if (f[0], f[3]) not in CONOCIDOS]

    print("=== estado declarado y nunca leido ===")
    if conocidas:
        print("  (%d conocido(s) y decidido(s), ver CONOCIDOS en este script: %s)"
              % (len(conocidas), ", ".join("%s.%s" % (f[0], f[3]) for f in conocidas)))
    if not filas:
        print("  ninguno")
        return 0
    filas.sort(key=lambda f: (-f[4], f[0], f[1]))
    ancho = "  %-22s %6s  %-4s %-26s %-11s %s"
    print(ancho % ("fichero", "linea", "tipo", "nombre", "escrituras", "ambito"))
    for f, ln, t, n, esc, amb in filas:
        marca = "  <-- se mantiene" if esc else ""
        print(ancho % (f, ln, t, n, esc, amb) + marca)
    conesc = sum(1 for f in filas if f[4])
    print()
    print("  %d en total, %d con escrituras." % (len(filas), conesc))
    if conesc:
        print("  Los que tienen escrituras son los que importan: alguien los")
        print("  mantiene al dia y nadie los consulta. Ahi es donde se esconde")
        print("  una funcion a medio conectar.")
    return 1 if (estricto and filas) else 0


if __name__ == "__main__":
    sys.exit(main())
