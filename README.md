# miniMerch NFT Studio (native Android) — DEPRECATED

> **⚠️ Merged into [miniMall Studio](https://github.com/eurobuddha/minima-core-android-merchstudio) 0.3.0**
> (package `com.eurobuddha.merchstudio`), which now has a **Products** tab (typed goods) and an
> **NFTs** tab (this app's entire gallery/StateNFT feature set). Install miniMall Studio from
> PandaApps; this app receives no further updates and is delisted from the store. Existing
> installs keep working, but new features land only in miniMall Studio.

The **NFT marketplace authoring tool** for miniMall: import your own NFTs straight from your
Minima node's wallet, price them, and export a portable **`.shop`** catalog your customers open
in the [miniMall](https://github.com/eurobuddha/minima-core-android-merchshop) app.
Package `com.eurobuddha.merchnftstudio`.

## How it works

- **Vendor identity** is derived automatically from **your node's own seed** (X25519 + Ed25519
  keys under the same `minimerch-*` HKDF domain as miniMerch Studio) — the same seed yields the
  same vendor identity in both studios, so orders land in the same miniMerch Inbox.
- **Import NFTs** — the app runs a single bounded `balance` query and lists every non-Minima
  token you hold with `decimals:0`: true 1-of-1 NFTs *and* multi-edition mints. Image, name and
  description are prefilled from the token metadata (`<artimage>` base64, data URIs, and
  http(s)/ipfs image URLs are all handled; image-less tokens get a deterministic identicon).
- **Price and list** — pick which NFTs to sell, set a price (Minima or mxUSDT) and how many
  editions to offer (capped at your sendable holding).
- **Export / share** — products go out in the standard `.shop` schema plus an additive
  `nftTokenId` field per product, so the Shop app can carry the tokenid into each order line and
  the [miniMerch Inbox](https://github.com/eurobuddha/minima-core-android-merchinbox) can offer
  one-tap on-chain **Send NFT** delivery to the buyer's pay address after payment.

No server: the shop is a file, the vendor identity is seed-derived, orders flow over the shared
**MINIMERCH** sentinel `0x4D494E494D45524348` with sealed-box crypto, and the NFTs themselves are
delivered as ordinary Minima token sends.

Note: unlike miniMerch Studio this app holds the `INTERNET` permission — NFT images referenced by
http(s)/ipfs URL in token metadata are fetched by the app (SSRF-guarded, size-capped). All node
communication remains local broadcast IPC.

## Build

Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install, then enable **miniMerch NFT Studio** in Minima Core → Apps (needed to derive your vendor
identity and read your wallet balance). Freshly minted NFTs appear once their mint is confirmed.

Current: **v0.1.0** · package `com.eurobuddha.merchnftstudio`.
