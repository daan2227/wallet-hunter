# Wallet Hunter

Android app for Bitcoin key recovery and auditing: a BIP39 seed scanner, a
puzzle-range solver, a wallet manager, a **weak-key audit** built on Pollard's
Kangaroo over secp256k1, and a grouped **Tools** screen with ~25 utilities
(addresses, signing, BIP38, PSBT, Shamir, vanity, a deterministic BSGS range
solver, brainwallet and nonce-reuse audits, and more).

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
- **BSGS** (baby-step/giant-step) solves a public key in a *small* known range
  deterministically, with negation symmetry (checks `k = a+i·m ± j` on each hit)
  and a self-verify (`k·G == P`) so a bug makes it *miss*, never return a wrong
  key. One shared baby table can audit many public keys over the same range.
- Address lookups use a memory-mapped 9-byte index over a sorted dataset with a
  blocked Bloom filter, so a candidate is rejected without touching disk.
- The ARMv8 SHA-256 / SHA-512 instructions are detected at runtime
  (`getauxval`); on a device without the crypto extensions it falls back to a
  software path instead of crashing.

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

> **Note — there is no hidden "pattern" to exploit.** A recurring claim on the
> puzzle forums is that the keys are a deterministic, hand-built sequence you can
> derive one from another. Tested against the 70 published solved keys, that is
> false: each key sits in `[2^(N-1), 2^N)` (the *only* real structure, and it's
> already what every tool searches), but **within** that range the normalized
> positions are uniform and uncorrelated (lag-1 correlation ≈ 0.06). The
> "single truncated seed" theory fails too — `k(m) mod 2^n == k(n)` holds for
> only 66 of 2415 pairs, decaying as `1/2^n` exactly like chance, not the
> near-100% a nested construction would give. Append-bit, parity and digit tests
> show nothing either. In short: an unsolved puzzle can only be attacked by
> searching its range (Kangaroo / BSGS / brute force) — anyone claiming to
> derive "any key regardless of length" would simply have swept the unclaimed
> prizes, which are still there.

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

Open it from the **Weak** tab in the bottom bar. There's also an in-app **Help**
screen (More → Help) with this same flow.

### 1. Input

Paste public keys (`02…/03…/04…`) or spent addresses (`1…/3…/bc1…`), one per
line — or **Load CSV / text file**. The file is read line by line and every
key/address in any column is pulled out (headers, quotes and extra columns are
ignored), without duplicates.

- Up to **2,000** entries go into the editor.
- More than that switch to **large-list mode**: kept in memory with just a
  summary (`file · N keys loaded`), so the editor stays responsive.
- Files over ~80 MB are **streamed from disk** as the audit runs (no RAM blow-up
  on multi-GB lists). x-only (taproot) keys are lifted to even-`y`.
- **From xpub / ypub / zpub…** derives a wallet's child public keys and audits
  those directly (no network needed).

**Resume / checkpoint.** Progress is saved **per list** (keyed by content), so
you can stop and later continue from the exact key you were on — and swapping
between several CSVs keeps each one's own progress. It survives closing the app.

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

Entries that didn't resolve are offered for **export / retry**: ones that failed
on the network (tap **Retry unreachable**) and ones with no published public key
(tap to copy).

**Audit log.** A terminal-style panel keeps a one-line history per run — source
(CSV name / `xpub` / pasted keys), checked / total, how many are left, whether
there were finds, the state (done / paused) and a timestamp. It shows the last
10 runs with a line to expand the older ones, and survives restarts.

### BSGS — deterministic small ranges

Next to **Start audit** there's **Solve with BSGS (small range)**: for ranges up
to ~2^44 it builds one shared baby table and resolves *all* the loaded public
keys deterministically (no probabilistic variance), offline — it skips plain
addresses, since BSGS needs the public key. It drives the same performance card
and sends finds to the vault. For bigger ranges use **Start audit** (Kangaroo).

### In short

Paste or load keys → set range, budget and cap → Kangaroo runs key by key over a
small range → whatever it finds (badly generated keys) goes to the vault.

## Tools

A grouped screen of standalone utilities (More → Tools), each a collapsible
card. Everything runs on-device; the few that need the network are watch-only.

- **Keys & addresses** — Addresses from a key · Hex ↔ WIF · Key / address
  inspector · Mnemonic tools (BIP39) · Custom derivation path · xpub → addresses.
- **Sign, encrypt & backup** — Sign & verify message · **BIP38** (encrypt a key,
  both non-EC and EC-multiplied) · **Shamir** split (k-of-n) · Key split (XOR).
- **Transactions** — Balance & UTXOs (watch-only) · Fee estimator · Tx size & fee
  calc · Sweep a key · Broadcast raw tx · Transaction decoder · **PSBT**
  (decode / sign) · Script decoder.
- **Monitor & generate** — Watch addresses · **Vanity address** (grinds a
  prefix on the native engine; P2PKH `1…`, P2SH `3…` or bech32 `bc1q…`) ·
  Dice / coin → key · QR code.
- **Audit & analysis** — **Solve key in range (BSGS)** · **Nonce-reuse audit**
  (scans a spent address; recovers the key from two signatures that reused `k`,
  and if there's no reuse it tries the lattice attack) · **Nonce-leak recovery
  (lattice)** (recovers a key when several signatures from the same key used
  short/biased nonces — the "Biased Nonce Sense" HNP + LLL attack, validated in
  `tools/ec-harness/lattice_hnp.py`) · **RFC6979 nonce check** (deterministic vs.
  random nonce) · **Signature low-S / malleability** (BIP62/BIP146) · **Brainwallet
  check** (phrase list vs. target(s), or just list addresses; optional `~60×`
  variation expansion — case, leet, common suffixes) · **Mnemonic strength**
  (low-entropy red flags in a BIP39 phrase) · Entropy checker · Difficulty / time.
- **Utility** — BTC ↔ sat.

## Finds vault

Every hit — from the scanner, a puzzle or the weak-key audit — lands here with
its WIF and address. From a find you can check its balance, move it to your
wallets, or delete it. It's encrypted on the device.

## More

**Tools** opens the grouped utilities screen above. **Notifications** toggles the
find alert, vibration and the coin sound on a find, each independently.
**Appearance** switches light/dark. **Debug** shows the engine log and files. And
the **Help** screen, which mirrors this document in the app. (Scanner, Puzzle,
Wallet, Recovery and Weak-key each have their own tab in the bottom bar.)
