# Graph Report - nftstudio  (2026-08-09)

## Corpus Check
- 32 files · ~19,652 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 347 nodes · 789 edges · 21 communities (16 shown, 5 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 29 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `02cbf10b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MainActivity
- CommsIdentity
- CommsScanner
- NodeApi
- Util
- MerchDb
- ImageLoader
- StateNft
- .view
- Identicon
- MainActivity.java
- QrUtil
- GalleryAdapter
- gradlew
- Context
- LazySodium
- Override
- TextView

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 66 edges
2. `StateNft` - 25 edges
3. `NodeApi` - 21 edges
4. `MerchDb` - 20 edges
5. `NftListing` - 18 edges
6. `CommsScanner` - 15 edges
7. `ImageLoader` - 15 edges
8. `Cb` - 11 edges
9. `Util` - 11 edges
10. `CommsIdentity` - 11 edges

## Surprising Connections (you probably didn't know these)
- `CommsScanner` --references--> `NodeApi`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/CommsScanner.java → app/src/main/java/com/eurobuddha/comms/NodeApi.java
- `MainActivity` --references--> `NodeApi`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/merchnftstudio/MainActivity.java → app/src/main/java/com/eurobuddha/comms/NodeApi.java
- `NftListing` --references--> `Meta`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/merchnftstudio/MainActivity.java → app/src/main/java/com/eurobuddha/merchnftstudio/StateNft.java
- `StateCollection` --references--> `Meta`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/merchnftstudio/MainActivity.java → app/src/main/java/com/eurobuddha/merchnftstudio/StateNft.java
- `MerchDb` --implements--> `MetaStore`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/MerchDb.java → app/src/main/java/com/eurobuddha/comms/CommsScanner.java

## Import Cycles
- None detected.

## Communities (21 total, 5 thin omitted)

### Community 0 - "MainActivity"
Cohesion: 0.15
Nodes (9): JSONObject, MainActivity, NftListing, StateCollection, EditText, LinearLayout, TextView, TokenBalance (+1 more)

### Community 1 - "CommsIdentity"
Cohesion: 0.07
Nodes (15): BackupCrypto, SecureRandom, CommsIdentity, LazySodium, Hex, Hkdf, LazySodium, Override (+7 more)

### Community 2 - "CommsScanner"
Cohesion: 0.12
Nodes (8): CommsScanner, JSONArray, JSONObject, Listener, MetaStore, Router, CryptoProvider, Opened

### Community 3 - "NodeApi"
Cohesion: 0.12
Nodes (11): CommsTransport, JSONObject, SendCb, Cb, Handler, JSONObject, NodeApi, PairingListener (+3 more)

### Community 4 - "Util"
Cohesion: 0.11
Nodes (5): JSONObject, TokenBalance, TokenMeta, JSONObject, Util

### Community 5 - "MerchDb"
Cohesion: 0.11
Nodes (7): Context, Override, MerchDb, Order, MerchMessage, SQLiteDatabase, SQLiteOpenHelper

### Community 6 - "ImageLoader"
Cohesion: 0.24
Nodes (5): Activity, ImageLoader, Bitmap, ImageView, LruCache

### Community 7 - "StateNft"
Cohesion: 0.14
Nodes (7): IconResolver, Item, JSONArray, JSONObject, Meta, StateNft, Pattern

### Community 8 - ".view"
Cohesion: 0.20
Nodes (8): Avatars, Context, Design, Context, TextView, FrameLayout, GradientDrawable, LayoutParams

### Community 9 - "Identicon"
Cohesion: 0.41
Nodes (4): Identicon, Bitmap, Canvas, Paint

### Community 10 - "MainActivity.java"
Cohesion: 0.17
Nodes (10): Images, Bitmap, Context, LazySodium, Sodium, Handler, AppCompatActivity, LazySodium (+2 more)

### Community 12 - "GalleryAdapter"
Cohesion: 0.25
Nodes (5): Adapter, GalleryAdapter, Bundle, Override, ViewHolder

### Community 13 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **5 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `NodeApi` connect `NodeApi` to `MainActivity`, `CommsScanner`, `MainActivity.java`, `GalleryAdapter`?**
  _High betweenness centrality (0.341) - this node is a cross-community bridge._
- **Why does `MainActivity` connect `MainActivity` to `.view`, `MainActivity.java`, `NodeApi`, `GalleryAdapter`?**
  _High betweenness centrality (0.338) - this node is a cross-community bridge._
- **Why does `CommsScanner` connect `CommsScanner` to `NodeApi`?**
  _High betweenness centrality (0.146) - this node is a cross-community bridge._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.14536340852130325 - nodes in this community are weakly interconnected._
- **Should `CommsIdentity` be split into smaller, more focused modules?**
  _Cohesion score 0.06802721088435375 - nodes in this community are weakly interconnected._
- **Should `CommsScanner` be split into smaller, more focused modules?**
  _Cohesion score 0.1164021164021164 - nodes in this community are weakly interconnected._
- **Should `NodeApi` be split into smaller, more focused modules?**
  _Cohesion score 0.12121212121212122 - nodes in this community are weakly interconnected._