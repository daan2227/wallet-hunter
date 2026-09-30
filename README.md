# Wallet Hunter

Android app for Bitcoin key recovery and auditing: a BIP39 seed scanner, a
puzzle-range solver, a wallet manager, and a **weak-key audit** built on
Pollard's Kangaroo over secp256k1.

The APK is built by GitHub Actions (`.github/workflows/build.yml`) on every push
to `main` and to `claude/**` branches.

## Performance

Everything heavy runs in native code (secp256k1 field math in ARM64 asm, NEON
`hash160x4`, an unrolled RIPEMD-160), spread across cores with a **Threads** and
**CPU limit** control on each screen.

- **Kangaroo** (weak-key audit, and puzzles once the public key is published)
  costs about `c·√(2^bits)` operations and measures ~1.5–1.7·√W on device.
  Measured speeds: **~7 M op/s on an A34**, **~18–20 M op/s on an A56**. The
  weak-key ETA measures the device's real speed while it runs and remembers it
  between sessions, so the estimate matches the phone instead of a fixed rate.
- **PBKDF2-HMAC-SHA512** (the bulk of turning a BIP39 phrase into addresses) is a
  custom implementation with five loop modes — software, ARMv8.2 SHA-512
  instructions, those interleaved ×2 and ×4, and an OpenSSL block path — and
  auto-calibrates to the fastest per device: about **1.3× faster on the A34** and
  **2.5× on the A56** versus the generic path.
- **Scanner** Direct-key mode is roughly **1,400× faster** than BIP39, since it
  skips PBKDF2 and seed derivation entirely.
- Address lookups use a memory-mapped 9-byte index over a sorted dataset with a
  blocked Bloom filter, so a candidate is rejected without touching disk.

## Scanner

Brute-forces keys against a loaded address dataset. Two modes: **BIP39**
(generates seed phrases and derives their addresses) or **Direct key** (raw
private keys, ~1,400× faster). It derives each candidate's addresses and checks
them against the dataset index (**Dataset → Load** a `.bin` file). **Threads** and
**CPU limit** cap how hard it pushes; **Derivation paths** picks which BIP paths
to try. Shows keys/s and an ETA.

## Puzzle

Solves the Bitcoin puzzle addresses, whose private key sits in a known range.
Pick a puzzle `#N` and it scans that range (progress in blocks, jump to a random
point, reset). If the puzzle's public key is published it switches to **Kangaroo**
over the From–To range, finding it in `√` of the range. It also checks the
balance and whether the public key is published.

## Wallet

Your wallets: create or import them, see balances (checked when the screen opens,
or with **Check balances**), and keep the key safe under **Safekeeping**. Deleting
a wallet warns you with its name and last known balance. Finds you decide to keep
move here.

## Recovery

Recovers a seed with missing words. Type the words you remember and mark each gap
with `?`; it tries the combinations for the blanks until a derived address
matches, then lets you add it to your wallets. It shows how many combinations
there are before you start, so you know if it's feasible.

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

## Finds vault

Every hit — from the scanner, a puzzle or the weak-key audit — lands here with
its WIF and address. From a find you can check its balance, move it to your
wallets, or delete it. It's encrypted on the device.

## More

**Appearance** switches light/dark. **Debug** shows the engine log and files. And
the **Help** screen, which mirrors this document in the app. Recovery and the
weak-key audit are opened from here too.
