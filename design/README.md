# Rediseño — lienzo de diseño

Maquetas de las pantallas de la app, en la dirección **Bóveda**. Cada `.dc.html`
es una pantalla; `canvas.json` las coloca en el lienzo.

Publicado en https://claude.ai/artifact/AHccMLXW8p73hNmfenK1QU

## Pantallas

| Fichero | Pantalla |
|---|---|
| `Main.dc.html` | Escáner |
| `Puzzle.dc.html` | Puzzle |
| `Cartera.dc.html` | Cartera (hub) |
| `VerCartera.dc.html` | Ver cartera · pestaña Saldo |
| `Enviar.dc.html` | Enviar |
| `Recovery.dc.html` | Recuperar seed |
| `Historial.dc.html` | Historial de sesiones |
| `Clave.dc.html` | Clave de acceso |

Recibir no está dibujada: es el QR, la dirección y poco más.

## El sistema

**Tipografía** — Sora para cifras y títulos, Public Sans para el resto.
Escala 44 / 22 / 17 / 14 / 12: una cifra domina por pantalla, lo demás baja
a 14 o 12.

**Superficies** — fondo `#0E0E0E`, tarjeta `#161616`, separador `#222`.
Sin bordes: la elevación la da el tono.

**Acento** — `#00C896` sólo en tres sitios: acción principal, saldo positivo
y estado "buscando". En ningún otro. Hoy la app lo usa además para el dataset,
el ritmo y los BTC disponibles, y ahí el color deja de significar algo.

El resto de valores vienen de `AppTheme.kt` y de los colores literales de las
activities: `#FF6B35` aviso, `#F04040` rojo, `#6EA8FE` azul.

Iconos de trazo a 19-20px, ningún emoji. Blancos de toque de 50-62px.
Las ocho pantallas caben en 390×844 sin scroll.

## Historial

La versión 1 del lienzo comparaba tres direcciones (Instrumento, Bóveda,
Consola) sobre escáner, puzzle y cartera. Elegida Bóveda, los bocetos
descartados se borraron y el sistema se aplicó a las siete pantallas.

## Reconstruir

El `.html` publicado es un artefacto generado y no se versiona (ver
`.gitignore`). Para rehacerlo hace falta el skill `design` de Claude Code,
que aporta la plantilla:

```
node <skill>/seed-canvas.mjs \
  --template <skill>/payload.template.html \
  --out wallet-hunter-rediseno.html \
  --title "Wallet Hunter · Rediseño" \
  --artboard Main.dc.html --artboard Puzzle.dc.html --artboard Cartera.dc.html \
  --artboard VerCartera.dc.html --artboard Enviar.dc.html \
  --artboard Recovery.dc.html \
  --artboard Historial.dc.html --artboard Clave.dc.html \
  --canvas canvas.json
```
