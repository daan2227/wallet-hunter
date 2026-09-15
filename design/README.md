# Rediseño — lienzo de diseño

Maquetas de las pantallas de la app. Cada `.dc.html` es una pantalla; `canvas.json`
las coloca en el lienzo y define las dos páginas.

Publicado en https://claude.ai/artifact/AHccMLXW8p73hNmfenK1QU

## Qué hay

Tres direcciones aplicadas a las tres pantallas principales, para comparar:

| | Escáner | Puzzle | Cartera |
|---|---|---|---|
| **A · Instrumento** | `Main.dc.html` | `PuzzleA.dc.html` | `WalletA.dc.html` |
| **B · Bóveda** | `ScanB.dc.html` | `PuzzleB.dc.html` | `WalletB.dc.html` |
| **C · Consola** | `ScanC.dc.html` | `PuzzleC.dc.html` | `WalletC.dc.html` |

Y el resto de pantallas en la dirección A: `BtcWalletA`, `RecoveryA`,
`StatsA`, `PasscodeA`.

## Valores

Tomados de `AppTheme.kt` y de los colores literales de las activities, no
inventados: fondo `#090909`, paneles `#111111`/`#141414`, acento `#00C896`,
texto `#EFEFEF`/`#868686`/`#444444`, bordes `#242424`, aviso `#FF6B35`,
rojo `#F04040`, azul `#6EA8FE`.

Las tres direcciones caben en 390×844 sin scroll.

## Reconstruir

El `.html` publicado es un artefacto generado y no se versiona (ver
`.gitignore`). Para rehacerlo hace falta el skill `design` de Claude Code,
que aporta la plantilla:

```
node <skill>/seed-canvas.mjs \
  --template <skill>/payload.template.html \
  --out wallet-hunter-rediseno.html \
  --title "Wallet Hunter · Rediseño" \
  --artboard Main.dc.html --artboard ScanB.dc.html ... \
  --canvas canvas.json
```
