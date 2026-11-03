# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The owner, a single technical Bitcoin user running the app on their own Android phone (Samsung A-series class). They use it for:
- scanning for keys;
- managing and moving their own wallets;
- mining on public-pool (CPU about 51–54 MH/s, GPU about 11 MH/s);
- running puzzle, weak-key and recovery audits.

Sessions are long and often left running: a scan or the miner works while the phone sits on a desk. The user glances at it for a figure, then checks a wallet or sends a payment.

## Product Purpose

Hunter Suite is one app for Bitcoin key recovery, auditing and self-custody:
- seed and direct-key scanner;
- puzzle-range solver;
- weak-key audit (Pollard's Kangaroo, BSGS);
- recovery tools;
- a full wallet (send, receive, history, Lightning, PSBT, multisig);
- a solo/pool miner;
- a pruned Bitcoin Core node on the phone;
- about 25 utilities.

Success means the user trusts what every figure says (balance, speed, ETA, hits). They also need to move money safely without mistakes.

## Positioning

The heavy work runs in native ARM64 code on the phone itself, with no server:
- secp256k1 asm;
- NEON hash160;
- ARMv8 SHA;
- a Vulkan GPU miner;
- bitcoind on the device.

It is an instrument and a vault in one pocket. The figures are measured on the user's own device.

## Operating Context

- **Long-running jobs:**
  - the scanner, Kangaroo, the miner and Core sync run for hours with the screen on or off;
  - the speed, progress and ETA figures are the main thing read.
- **Money flows:** send (with review dialog, fee choice, PSBT), receive (QR, BIP21), history (tax report, CSV).
- **Security:**
  - an encrypted vault holds seeds and WIF keys, behind PIN and fingerprint;
  - the user cares a lot about protecting seeds and keys.
- **Display:** dark use at night is common, and a day theme exists.

## Capabilities and Constraints

- Native Android with programmatic Views (Kotlin, no XML layouts, no Compose). All styling comes from `AppTheme.kt` tokens and `Ui.kt` helpers.
- Builds only in CI (GitHub Actions). There is no emulator in the agent environment, so visual checks happen as HTML mockups first and on the device after the APK is built.
- Screens:
  - bottom bar today: Scanner, Puzzle, Wallet, Recovery, Weak, Core, More (7 items);
  - activities: Wallet, Miner, Vault, Lightning, Security, Taproot audit, Tools, Help, Debug.
- UI copy is in English; the owner speaks Spanish.

## Brand Commitments

- **Theme:** only "Vault Gold" (dark slate `#0F172A` with gold `#F59E0B`) and its day version (`#F8FAFC` with gold `#B45309`). The owner chose this explicitly and it is binding.
- **Type:** IBM Plex Sans with JetBrains Mono for data; the figures are tabular.
- **Icons:** a stroke icon set (24 grid, 1.8 stroke) with no emoji. Status symbols are drawn as vector icons.

## Evidence on Hand

- The owner's screenshots of the miner (earlier sessions).
- Measured speeds in README.md.
- There are no testimonials or marketing claims, and none should be invented.

## Product Principles

1. A figure is only shown if it is true. When a lookup fails, the app says so rather than showing zero.
2. Money actions are never ambiguous: review before send, confirmation before anything irreversible.
3. Long jobs are glanceable: the one figure that matters is readable from arm's length.
4. Power stays reachable but out of the way: advanced tools live behind a clear door, not on the main path.

## Accessibility & Inclusion

- Contrast of at least 4.5:1 in both themes.
- Touch targets of at least 48 dp.
- TalkBack labels on icon buttons.
- Live regions for status changes.
- The system setting that removes animations is respected.
