# Wallet Hunter

Android app for Bitcoin key recovery and auditing: a BIP39 seed scanner, a
puzzle-range solver, a wallet manager, and a **weak-key audit** built on
Pollard's Kangaroo over secp256k1.

The APK is built by GitHub Actions (`.github/workflows/build.yml`) on every push
to `main` and to `claude/**` branches.

## Weak-key audit — how it works

Kangaroo needs the **public key** *and* a **range** where the private key lives.
For a normal address the range is all of `[1, 2^256]` — impossible. The only
thing you can audit is a **badly generated** key: one whose private value came
from too little entropy and landed in a small range (a 32-bit `rand()`, a
timestamp, a short passphrase hashed and truncated…). For those, if the public
key is published (the address has **spent**), Kangaroo over `[1, 2^bits)` finds
it in `√` of the range.

A properly generated key (256 bits of entropy) will never show up here — the
range is an infinitesimal slice of the space. Use it to check **your own**
addresses, or a system you audit, not to sweep third parties'.

Open it from **More → Weak-key audit**. There's also an in-app **Help** screen
(More → Help) with this same flow.

### 1. Input

Paste public keys (`02…/03…/04…`) or spent addresses (`1…/3…/bc1…`), one per
line — or **Load CSV / text file**. The file is read line by line and every
key/address in any column is pulled out (headers, quotes and extra columns are
ignored), without duplicates.

- Up to **2,000** entries go into the editor.
- More than that switch to **large-list mode**: kept in memory with just a
  summary (`file · N keys loaded`), so the editor stays responsive.

### 2. Settings

- **Search range** (20–80 bits, suggested 50): how far it looks. It only finds
  keys whose private value is below `2^bits`.
- **Budget × √** (2–4): margin over `√(2^bits)`. Higher = surer but slower.
- **Max seconds per key** (`0` = no limit): cuts a key that drags on and moves
  to the next.

A live line shows operations per key, time per key at the device's **measured**
speed (remembered between runs), and the **batch ETA** (number of keys × time
per key).

### 3. Run

**Start audit** builds the queue (editor + large list, deduped) and works one
key at a time:

1. A public key (`02/03/04`) is used directly.
2. An address is resolved to its public key over the network — which only exists
   if the address has spent; if it can't be found, it's skipped.
3. Kangaroo runs over `[1, 2^bits)` with budget `c·√(2^bits)` on all cores.

Every second it checks whether the key surfaced, the budget ran out, or the
per-key time cap was hit — then moves on.

### 4. Performance card

While it runs: live speed with its peak, a trend chart, and four figures
(Operations · Time · Key budget % · Keys · found). It also measures and
remembers the device's speed to keep future ETAs accurate.

### 5. Results

Each hit is saved to the **finds vault** (with its WIF and address) and a
notification pops up. At the end: `Done: N checked · M found → in the finds
vault.` Review or delete finds from the vault.

### In short

Paste or load keys → set range, budget and cap → Kangaroo runs key by key over a
small range → whatever it finds (badly generated keys) goes to the vault.
