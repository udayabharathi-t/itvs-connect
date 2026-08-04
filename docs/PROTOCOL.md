# TVS SmartXonnect BLE protocol (v1 subset)

Derived from [JupiterRideCompanion REVERSE_ENGINEERING_REPORT](https://github.com/overclock98/JupiterRideCompanion/blob/main/REVERSE_ENGINEERING_REPORT.md).

## GATT

| Item | UUID |
|---|---|
| Service | `5456534d-5647-5341-5342-454e544f5251` |
| Write | `00005352-0000-1000-8000-00805f9b34fb` |
| Notify / Read | `00005354-0000-1000-8000-00805f9b34fb` |

AES-128 key: `7A A3 20 4D 16 1D B5 33 F4 EB 20 4F BC D7 3D D4`  
Mode: `AES/CTR/NoPadding`, IV = challenge bytes.

## Auth

1. Enable CCCD on notify characteristic.
2. Challenge: `0x9A 0xF2` + 16 random bytes.
3. Response: `0x9A 0xF1 0x50` + random offset/length slice of ciphertext + `0xFF`.
4. Follow with User ID (`0x22`) and Rider Name (`0x52`) packets.

## Ping / Find Me (`0x4A`)

20-byte plaintext frame starting `0x5B`, ending `0xFF`.  
Checksum at byte 18: `255 - (sum(bytes[0..17]) % 256)`.  
Byte 17 = `1` triggers Find Me (horn/lights). Heartbeat every 2s.

## Telemetry (inbound)

| ID | Fields |
|---|---|
| `0x10` | Odo u24@3-5 `/10` km; fuel bars nibble@6; call cmd@13 |
| `0x11` | Service reminder@4 (ignition marker) |
| `0x18` | Ignition marker (no payload parse) |
| `0x19` | IFE km/L@7 (when valid); AFE km/L@8; DTE u16@11-12. Live HUD prefers IFE, else AFE. Invalid/0 → no live sample (N/A). |
| `0x54` | Music command@2 |
| `0x6B` | Dialer command@2 |

## Messages

`0x4C` / `0x63` — 17 UTF-8 chars per row, no checksum.

## Button detection

Hold ≈ ≥3 consecutive `0x10` packets; release on next `0x11`/`0x18`/`0x19`.  
App coalesces taps with a configurable double-press window.

## Out of scope (v2)

Navigation control packets `0x4E` / `0x4F` / `0x50` and Maps notification harvesting.
