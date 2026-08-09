package com.eurobuddha.merchnftstudio;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.Images;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Sodium;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * miniMerch NFT Studio — an art-first gallery of the NFTs in YOUR wallet. Tap to list, price it,
 * preview the exact buyer storefront, export a .shop. Delivery is on-chain and automatic: the
 * buyer's wallet address rides in the order, and miniMall Inbox sends the NFT on confirmed payment.
 */
public class MainActivity extends AppCompatActivity {

    private LazySodium ls;
    private NodeApi node;
    private boolean paired = false;
    private String vendorPublicId = "", vendorAddress = "";

    private String shopName = "", about = "";
    private String currency = "Minima", tokenid = CommsTransport.MINIMA;

    /** One row per listable NFT. Keyed by tokenid for plain NFTs, tokenid#idx for StateNFT pieces.
     *  A Refresh merges fresh data in while PRESERVING the vendor's listings + edits. */
    private final LinkedHashMap<String, NftListing> listings = new LinkedHashMap<>();
    /** StateNFT collections detected in the wallet (script-fingerprinted), keyed by tokenid. */
    private final LinkedHashMap<String, StateCollection> collections = new LinkedHashMap<>();
    /** Memoized `tokens tokenid:` records — token scripts are immutable, fetch once per process. */
    private final java.util.HashMap<String, JSONObject> tokenRecords = new java.util.HashMap<>();
    private final java.util.HashSet<String> fetchingRecords = new java.util.HashSet<>();
    private boolean nftsLoading = false, nftsLoaded = false;
    private boolean exporting = false;

    private static final int MAX_PRODUCTS = 40;   // .shop schema cap

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ArrayDeque<View> stack = new ArrayDeque<>();
    private LinearLayout root;
    private FrameLayout container;

    /** A wallet NFT with the vendor's listing edits layered on top of the token metadata.
     *  Plain NFT: bal set, stateIdx 0. StateNFT piece: stateIdx ≥ 1, coinRaw + snMeta set, bal null.
     *  Complete-collection bundle: bundle=true, snMeta set — sells every piece the vendor holds. */
    private static final class NftListing {
        String tokenid = "";
        TokenBalance bal;
        int stateIdx = 0;                     // ≥1 = a piece of a StateNFT collection
        boolean bundle = false;               // the whole collection as ONE listing
        JSONObject coinRaw;                   // the piece's raw coin (byte-exact state)
        StateNft.Meta snMeta;                 // the piece's collection metadata
        boolean listed = false;
        String name = "", description = "";   // prefilled from token metadata, editable
        String price = "";
        String units = "";                    // editions to offer, default = sendable holding
        String cachedB64 = "";                // resolved shop image (bare base64 JPEG), lazily built
    }

    /** A StateNFT collection in the wallet: one tokenid, one coin per piece, art in coin state. */
    private static final class StateCollection {
        String tokenid = "";
        StateNft.Meta meta;
        boolean legacy = false;               // pre-SAMESTATE contract — creator can still rewrite state
        final java.util.List<JSONObject> ownedCoins = new java.util.ArrayList<>();  // stamped + replayable
        int unstampedCount = 0;
        boolean coinsLoading = false, coinsLoaded = false, tooLong = false;
    }

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);

        showGallery();
        node = new NodeApi(this, this::onPaired);
    }

    @Override protected void onDestroy() { super.onDestroy(); if (node != null) node.onDestroy(); io.shutdownNow(); }

    @Override public void onBackPressed() {
        if (stack.size() > 1) pop(); else super.onBackPressed();
    }

    // ---- navigation (view stack, as in miniMall) ----

    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showGallery() { stack.clear(); stack.push(buildGallery()); showTop(); }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }
    private void refreshIfTag(String tag) {
        View top = stack.peek();
        if (top != null && tag.equals(top.getTag())) {
            if ("gallery".equals(tag)) refreshTop(buildGallery());
            else if ("shop".equals(tag)) refreshTop(buildShopDetails());
        }
    }

    // ---- identity (vendor card) ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        if (enabled && vendorPublicId.isEmpty()) {
            node.cmd("vault action:seed", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    JSONObject r = j.optJSONObject("response");
                    String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                    if (!ikm.isEmpty()) deriveCard(ikm);
                }
                @Override public void onError(String m) {}
            });
            node.cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    JSONObject r = j.optJSONObject("response");
                    if (r != null) { vendorAddress = r.optString("miniaddress", r.optString("address", "")); refreshIfTag("gallery"); }
                }
                @Override public void onError(String m) {}
            });
        }
        if (enabled) loadNfts();
        refreshIfTag("gallery");
    }

    private void deriveCard(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                String pid = CommsIdentity.fromSeed(ls, seed).publicId();
                ui.post(() -> { vendorPublicId = pid; refreshIfTag("gallery"); });
            } catch (Exception e) { ui.post(() -> toast("Identity error: " + e.getMessage())); }
        });
    }

    private boolean cardReady() {
        return CommsIdentity.isValidPublicId(vendorPublicId) && vendorAddress != null && !vendorAddress.isEmpty();
    }

    // ---- wallet NFTs ----

    private void loadNfts() {
        if (nftsLoading) return;
        nftsLoading = true;
        refreshIfTag("gallery");
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                nftsLoading = false; nftsLoaded = true;
                mergeBalances(j.optJSONArray("response"));
                refreshIfTag("gallery");
            }
            @Override public void onError(String m) {
                nftsLoading = false;
                if (!NodeApi.ERR_NOT_ENABLED.equals(m)) toast("Couldn't read wallet: " + m);
                refreshIfTag("gallery");
            }
        });
    }

    /** Keep every 0-decimal non-Minima token we can currently send; carry existing edits across.
     *  StateNFT collections (script-fingerprinted via ensureRecord) are promoted OUT of the plain
     *  list — their individual pieces are enumerated per-coin by loadCollectionCoins. */
    private void mergeBalances(JSONArray rows) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        LinkedHashMap<String, NftListing> fresh = new LinkedHashMap<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            TokenBalance t = TokenBalance.from(row);
            if (t.isMinima()) continue;
            String d = t.meta == null ? "" : t.meta.decimals;
            if (!(d.isEmpty() || d.equals("0"))) continue;           // fungible token — not listable here
            BigInteger held = wholeUnits(t.sendable);
            if (held.signum() <= 0) continue;                        // nothing spendable (yet)
            if (!Util.isValidHexId(t.tokenid)) continue;
            seen.add(t.tokenid);
            ensureRecord(t.tokenid);                                  // memoized script fingerprint
            if (collections.containsKey(t.tokenid)) continue;         // a collection, not a plain row
            NftListing l = listings.get(t.tokenid);
            if (l == null) {
                l = new NftListing();
                l.name = t.meta.name == null ? "" : t.meta.name;
                l.description = t.meta.description == null ? "" : t.meta.description;
                l.units = held.toString();
            }
            l.tokenid = t.tokenid;
            l.bal = t;
            fresh.put(t.tokenid, l);
        }
        // Drop collections that vanished from the wallet (with their piece listings); refresh the rest.
        for (java.util.Iterator<Map.Entry<String, StateCollection>> it = collections.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, StateCollection> e = it.next();
            if (!seen.contains(e.getKey())) {
                it.remove();
                listings.keySet().removeIf(k -> k.startsWith(e.getKey() + "#"));
            } else {
                loadCollectionCoins(e.getValue());
            }
        }
        // Carry surviving piece listings across the rebuild (managed by loadCollectionCoins).
        for (Map.Entry<String, NftListing> e : listings.entrySet()) {
            if (e.getKey().contains("#") && collections.containsKey(e.getValue().tokenid)) fresh.put(e.getKey(), e.getValue());
        }
        listings.clear();
        listings.putAll(fresh);
    }

    /** Fetch (once per process) the token record and promote StateNFT collections. */
    private void ensureRecord(final String tokenid) {
        if (tokenRecords.containsKey(tokenid) || fetchingRecords.contains(tokenid)) return;
        fetchingRecords.add(tokenid);
        node.cmd("tokens tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                fetchingRecords.remove(tokenid);
                Object resp = j.opt("response");
                JSONObject tok = null;
                if (resp instanceof JSONObject) tok = (JSONObject) resp;
                else if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) tok = ((JSONArray) resp).optJSONObject(0);
                if (tok == null) return;
                tokenRecords.put(tokenid, tok);
                String script = tok.optString("script", "");
                if (!StateNft.isStateNftScript(script)) return;
                StateCollection col = new StateCollection();
                col.tokenid = tokenid;
                col.meta = StateNft.parseMeta(tokenid, tok);
                col.legacy = !script.startsWith("LET s=PREVSTATE");
                collections.put(tokenid, col);
                listings.remove(tokenid);                     // no longer a plain aggregate row
                loadCollectionCoins(col);
                refreshIfTag("gallery");
            }
            @Override public void onError(String m) { fetchingRecords.remove(tokenid); }
        });
    }

    /** Enumerate the collection's coins in this wallet (bounded per-token query); rebuild piece
     *  listings keyed tokenid#idx, PRESERVING listing edits (the coinid churns, the idx doesn't). */
    private void loadCollectionCoins(final StateCollection col) {
        if (col.coinsLoading) return;
        col.coinsLoading = true;
        node.cmd("coins relevant:true tokenid:" + col.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                col.coinsLoading = false; col.coinsLoaded = true; col.tooLong = false;
                col.ownedCoins.clear(); col.unstampedCount = 0;
                java.util.HashSet<String> liveKeys = new java.util.HashSet<>();
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || !Util.isValidHexId(c.optString("coinid", ""))) continue;
                    if (StateNft.isBuried(c)) continue;                           // graveyard coins still get tracked
                    String idx = StateNft.stamped(c);
                    if (idx == null) { col.unstampedCount++; continue; }          // sentinel — not listable
                    if (!idx.matches("^[0-9]+$") || !StateNft.replayableState(c)) continue;  // hostile state
                    col.ownedCoins.add(c);
                    String key = col.tokenid + "#" + idx;
                    liveKeys.add(key);
                    NftListing l = listings.get(key);
                    if (l == null) {
                        l = new NftListing();
                        l.name = col.meta.name + " #" + idx;
                        l.description = col.meta.description == null ? "" : col.meta.description;
                        l.units = "1";
                        listings.put(key, l);
                    }
                    l.tokenid = col.tokenid;
                    l.stateIdx = Integer.parseInt(idx);
                    l.coinRaw = c;
                    l.snMeta = col.meta;
                }
                // pieces that left the wallet are no longer listable
                listings.keySet().removeIf(k -> k.startsWith(col.tokenid + "#") && !liveKeys.contains(k));
                refreshIfTag("gallery");
                refreshIfCollection(col.tokenid);
            }
            @Override public void onError(String m) {
                col.coinsLoading = false;
                if (NodeApi.ERR_TOO_LONG.equals(m)) col.tooLong = true;
                refreshIfTag("gallery");
                refreshIfCollection(col.tokenid);
            }
        });
    }

    private void refreshIfCollection(String tokenid) {
        View top = stack.peek();
        if (top != null && ("collection:" + tokenid).equals(top.getTag())) {
            StateCollection col = collections.get(tokenid);
            if (col != null) refreshTop(buildCollectionGrid(col));
        }
    }

    private static BigInteger wholeUnits(String amount) {
        try { return new BigDecimal(amount == null ? "0" : amount.trim()).toBigInteger(); }
        catch (Exception e) { return BigInteger.ZERO; }
    }

    private List<NftListing> listedItems() {
        List<NftListing> out = new ArrayList<>();
        for (NftListing l : listings.values()) if (l.listed) out.add(l);
        return out;
    }

    private String displayName(NftListing l) {
        String n = l.name == null ? "" : l.name.trim();
        if (!n.isEmpty()) return n;
        if (l.bundle) return l.snMeta.name + " — complete collection";
        if (l.stateIdx > 0) return l.snMeta.name + " #" + l.stateIdx;
        return l.bal == null || l.bal.meta.name == null ? "NFT" : l.bal.meta.name;
    }

    private String editionChip(NftListing l) {
        if (l.bundle) {
            StateCollection col = collections.get(l.tokenid);
            return "◈ all " + (col == null ? "" : col.ownedCoins.size() + " ") + "pieces";
        }
        if (l.stateIdx > 0) return "◈ #" + l.stateIdx + " / " + l.snMeta.size;
        BigInteger supply = wholeUnits(l.bal.total);
        return BigInteger.ONE.equals(supply) ? "◈ 1 of 1" : "◈ ×" + supply + " eds";
    }

    /** The piece's art URI (embedded state art > url-mode > collection icon). */
    private String pieceImageUrl(NftListing l) {
        return StateNft.imageUrl(l.snMeta, l.stateIdx, l.coinRaw);
    }

    /** Art source for any listing/collection card; empty string = identicon only. */
    private String artUrlOf(NftListing l) {
        if (l.bundle) { String u = IconResolver.resolve(l.snMeta.icon); return u == null ? "" : u; }
        if (l.stateIdx > 0) { String u = pieceImageUrl(l); return u == null ? "" : u; }
        return l.bal != null && l.bal.hasIcon() ? l.bal.meta.iconUrl : "";
    }

    private String identiconKey(NftListing l) {
        if (l.bundle) return l.tokenid + "#ALL";
        return l.stateIdx > 0 ? l.tokenid + "#" + l.stateIdx : l.tokenid;
    }

    private String currencyLabel() { return "USDT".equals(currency) ? "mxUSDT" : "Minima"; }

    private boolean priced(NftListing l) {
        try { return new BigDecimal(l.price.trim()).signum() > 0; } catch (Exception e) { return false; }
    }

    // ==== SCREEN 1 · Gallery ====

    private View buildGallery() {
        LinearLayout col = column();
        col.setTag("gallery");
        col.addView(header("miniMerch NFT Studio", false));

        TextView status = new TextView(this);
        status.setTextSize(12f); status.setPadding(dp(16), dp(6), dp(16), dp(4));
        if (cardReady()) { status.setText("✓ Shop key ready (from this node's seed)"); status.setTextColor(Design.IN); }
        else if (!paired) { status.setText("Enable miniMerch NFT Studio in Minima Core → Apps."); status.setTextColor(Design.ACCENT); }
        else { status.setText("Connecting to your node…"); status.setTextColor(Design.DIM); }
        col.addView(status);

        LinearLayout nh = new LinearLayout(this); nh.setOrientation(LinearLayout.HORIZONTAL); nh.setGravity(Gravity.CENTER_VERTICAL);
        nh.setPadding(dp(16), dp(8), dp(16), dp(4));
        TextView nl = new TextView(this);
        nl.setText(("Your wallet · " + listings.size() + (listings.size() == 1 ? " NFT" : " NFTs")).toUpperCase());
        nl.setTextColor(Design.DIM2); nl.setTextSize(11f); nl.setTypeface(null, Typeface.BOLD);
        nl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nh.addView(nl);
        TextView refresh = button(nftsLoading ? "…" : "↻", false);
        refresh.setPadding(dp(12), dp(6), dp(12), dp(6));
        refresh.setOnClickListener(v -> { if (paired) loadNfts(); else toast("Connect your node first."); });
        nh.addView(refresh);
        col.addView(nh);

        List<Object> cards = new ArrayList<>(collections.values());
        for (Map.Entry<String, NftListing> e : listings.entrySet())
            if (!e.getKey().contains("#")) cards.add(e.getValue());

        if (!paired) {
            col.addView(hint("Enable this app in Minima Core → Apps to read your wallet."));
            col.addView(spacerWeight());
        } else if (nftsLoading && cards.isEmpty()) {
            col.addView(hint("Reading your wallet…"));
            col.addView(spacerWeight());
        } else if (cards.isEmpty() && nftsLoaded) {
            col.addView(hint("No NFTs found in this wallet.\n\nNFTs are Minima tokens minted with decimals:0. Freshly minted ones appear once confirmed."));
            col.addView(spacerWeight());
        } else {
            col.addView(hint("Tap an NFT to list it for sale — collections open into their pieces."));
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new GridLayoutManager(this, 2));
            rv.setPadding(dp(10), dp(4), dp(10), dp(10));
            rv.setClipToPadding(false);
            rv.setAdapter(new GalleryAdapter(cards, true));
            col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        int listed = listedItems().size();
        TextView cont = button(listed == 0 ? "List an NFT to continue" : "Continue · " + listed + " listed  →", listed > 0);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(dp(16), dp(8), dp(16), dp(14)); cont.setLayoutParams(cl);
        cont.setPadding(dp(16), dp(13), dp(16), dp(13));
        cont.setOnClickListener(v -> { if (!listedItems().isEmpty()) push(buildShopDetails()); });
        col.addView(cont);
        return col;
    }

    /** 2-col art grid over NftListing and StateCollection cards.
     *  galleryMode=true → studio cards (tap to list/edit); false → buyer-style preview. */
    private class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Object> data;
        final boolean galleryMode;
        GalleryAdapter(List<? extends Object> d, boolean galleryMode) { this.data = new ArrayList<>(d); this.galleryMode = galleryMode; }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            return new RecyclerView.ViewHolder(nftCardShell()) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            Object item = data.get(pos);
            if (item instanceof StateCollection) bindCollectionCard((LinearLayout) h.itemView, (StateCollection) item);
            else bindNftCard((LinearLayout) h.itemView, (NftListing) item, galleryMode);
        }

        @Override public int getItemCount() { return data.size(); }
    }

    /** Collection card: icon art, name, "◈ N pieces · you hold M", listed count. Tap → piece grid. */
    private void bindCollectionCard(LinearLayout card, final StateCollection col) {
        card.removeAllViews();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int listed = 0;
        boolean bundled = false;
        for (NftListing l : listings.values()) {
            if (!l.listed || !col.tokenid.equals(l.tokenid)) continue;
            if (l.bundle) bundled = true;
            else if (l.stateIdx > 0) listed++;
        }
        inner.setBackground(cardBg(listed > 0 || bundled));
        inner.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
        inner.setLayoutParams(ilp);

        ImageView art = squareImage();
        art.setImageBitmap(Identicon.forToken(col.tokenid, dp(160)));
        String icon = IconResolver.resolve(col.meta.icon);
        if (icon != null && !icon.isEmpty()) ImageLoader.loadOver(this, icon, art, null);
        inner.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nm = new TextView(this);
        nm.setText(col.meta.name); nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
        nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(dp(4), dp(8), dp(4), 0);
        inner.addView(nm);

        TextView ed = new TextView(this);
        ed.setText("◈ " + col.meta.size + " pieces · you hold " + col.ownedCoins.size());
        ed.setTextColor(Design.DIM); ed.setTextSize(11.5f); ed.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(ed);

        TextView pr = new TextView(this);
        pr.setText(col.coinsLoading && !col.coinsLoaded ? "reading pieces…"
                : (bundled ? "complete collection listed" : (listed > 0 ? listed + " listed" : "tap to view pieces")));
        pr.setTextColor(listed > 0 || bundled ? Design.ACCENT : Design.DIM2);
        if (listed > 0 || bundled) pr.setTypeface(null, Typeface.BOLD);
        pr.setTextSize(13f); pr.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(pr);

        inner.setOnClickListener(v -> push(buildCollectionGrid(col)));
        card.addView(inner);
    }

    /** Card shell: square art on top, text block under. Rebuilt cheaply on bind. */
    private LinearLayout nftCardShell() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        return card;
    }

    private void bindNftCard(LinearLayout card, final NftListing l, boolean galleryMode) {
        card.removeAllViews();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(cardBg(galleryMode && l.listed));
        inner.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
        inner.setLayoutParams(ilp);

        // square art
        FrameLayout artWrap = new FrameLayout(this);
        ImageView art = squareImage();
        art.setImageBitmap(Identicon.forToken(identiconKey(l), dp(160)));
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) ImageLoader.loadOver(this, artUrl, art, null);
        artWrap.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (galleryMode && l.listed) {
            TextView tick = new TextView(this);
            tick.setText("✓"); tick.setTextColor(Design.ON_ACCENT); tick.setTextSize(13f); tick.setTypeface(null, Typeface.BOLD);
            tick.setGravity(Gravity.CENTER); tick.setBackground(Design.roundBg(this, Design.ACCENT, 12));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
            tl.setMargins(0, dp(6), dp(6), 0); tick.setLayoutParams(tl);
            artWrap.addView(tick);
        }
        inner.addView(artWrap);

        TextView nm = new TextView(this);
        nm.setText(displayName(l)); nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
        nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(dp(4), dp(8), dp(4), 0);
        inner.addView(nm);

        TextView ed = new TextView(this);
        ed.setText(editionChip(l)); ed.setTextColor(Design.DIM); ed.setTextSize(11.5f);
        ed.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(ed);

        TextView pr = new TextView(this);
        if (galleryMode) {
            if (l.listed && priced(l)) { pr.setText(Util.tidyAmount(l.price) + " " + currencyLabel()); pr.setTextColor(Design.ACCENT); pr.setTypeface(null, Typeface.BOLD); }
            else if (l.listed) { pr.setText("listed — set a price"); pr.setTextColor(Design.ACCENT); }
            else { pr.setText("unlisted"); pr.setTextColor(Design.DIM2); }
        } else {
            pr.setText(Util.tidyAmount(l.price) + " " + currencyLabel());
            pr.setTextColor(Design.ACCENT); pr.setTypeface(null, Typeface.BOLD);
        }
        pr.setTextSize(13f); pr.setPadding(dp(4), dp(2), dp(4), 0);
        inner.addView(pr);

        if (galleryMode) {
            inner.setOnClickListener(v -> {
                if (!l.listed && listedItems().size() >= MAX_PRODUCTS) { toast("A shop holds at most " + MAX_PRODUCTS + " listings."); return; }
                if (!l.listed) {
                    // listing an individual piece supersedes a complete-collection listing
                    if (l.stateIdx > 0) { StateCollection c = collections.get(l.tokenid); if (c != null) unlistBundle(c); }
                    l.listed = true;
                }
                push(buildListingEditor(l));
            });
        } else {
            inner.setOnClickListener(v -> showFullArt(l));
        }
        card.addView(inner);
    }

    /** Rounded card background; accent stroke when listed. */
    private GradientDrawable cardBg(boolean listed) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.SURFACE);
        g.setCornerRadius(dp(12));
        if (listed) g.setStroke(dp(2), Design.ACCENT);
        return g;
    }

    /** ImageView measured square (height = width) — the marketplace-grid look. */
    private ImageView squareImage() {
        ImageView iv = new ImageView(this) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
        };
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Design.roundBg(this, Design.SURFACE2, 10));
        iv.setClipToOutline(true);
        return iv;
    }

    // ==== SCREEN 2 · Listing editor ====

    private View buildListingEditor(final NftListing l) {
        final boolean piece = l.stateIdx > 0;
        LinearLayout col = column();
        col.setTag("editor:" + identiconKey(l));
        col.addView(header("Price your listing", true));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // full-width art
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Design.roundBg(this, Design.SURFACE2, 14));
        art.setClipToOutline(true);
        art.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        art.setImageBitmap(Identicon.forToken(identiconKey(l), dp(220)));
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) ImageLoader.loadOver(this, artUrl, art, null);
        art.setOnClickListener(v -> showFullArt(l));
        body.addView(art);

        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.HORIZONTAL); meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dp(10), 0, 0);
        TextView nm = new TextView(this);
        nm.setText(piece ? l.snMeta.name + " #" + l.stateIdx : l.bal.meta.name);
        nm.setTextColor(Design.TEXT); nm.setTextSize(17f); nm.setTypeface(null, Typeface.BOLD);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        meta.addView(nm);
        TextView ed = new TextView(this);
        ed.setText(piece ? "Edition #" + l.stateIdx + " of " + l.snMeta.size : editionChip(l));
        ed.setTextColor(Design.DIM); ed.setTextSize(12.5f);
        meta.addView(ed);
        body.addView(meta);

        // ticker · tokenid · external link
        LinearLayout prov = new LinearLayout(this); prov.setOrientation(LinearLayout.HORIZONTAL); prov.setGravity(Gravity.CENTER_VERTICAL);
        TextView tid = new TextView(this);
        String tkr = !piece && l.bal.meta.ticker != null && !l.bal.meta.ticker.isEmpty() ? l.bal.meta.ticker + " · " : "";
        tid.setText(tkr + Util.shorten(l.tokenid));
        tid.setTextColor(Design.DIM2); tid.setTextSize(11.5f);
        tid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        prov.addView(tid);
        final String ext = piece ? l.snMeta.externalUrl : l.bal.meta.externalUrl;
        if (ext != null && (ext.startsWith("http://") || ext.startsWith("https://"))) {
            TextView link = new TextView(this); link.setText("↗"); link.setTextColor(Design.ACCENT); link.setTextSize(14f);
            link.setPadding(dp(8), 0, 0, 0);
            link.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ext))); } catch (Exception ignored) {} });
            prov.addView(link);
        }
        body.addView(prov);

        body.addView(sectionLabel("Name"));
        body.addView(field(l.name, "Listing name", v -> l.name = v));
        body.addView(sectionLabel("About"));
        EditText desc = field(l.description, "Tell buyers about this piece (optional)", v -> l.description = v);
        desc.setMinLines(2); body.addView(desc);

        body.addView(sectionLabel("Price"));
        LinearLayout pr = new LinearLayout(this); pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
        EditText price = field(l.price, "0", v -> l.price = v);
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        price.setLayoutParams(new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        pr.addView(price);
        TextView cur = new TextView(this); cur.setText("  " + currencyLabel()); cur.setTextColor(Design.DIM); cur.setTextSize(14f);
        pr.addView(cur);
        body.addView(pr);

        // editions stepper (only meaningful for multi-edition holdings; a piece is always unique)
        final BigInteger held = piece ? BigInteger.ONE : wholeUnits(l.bal.sendable);
        if (held.compareTo(BigInteger.ONE) > 0) {
            body.addView(sectionLabel("Editions for sale"));
            LinearLayout st = new LinearLayout(this); st.setOrientation(LinearLayout.HORIZONTAL); st.setGravity(Gravity.CENTER_VERTICAL);
            final TextView qty = new TextView(this);
            qty.setTextColor(Design.TEXT); qty.setTextSize(16f); qty.setGravity(Gravity.CENTER); qty.setMinWidth(dp(44));
            final Runnable render = () -> qty.setText(clampUnits(l, held).toString());
            TextView minus = roundBtn("–"); TextView plus = roundBtn("+");
            minus.setOnClickListener(v -> { l.units = clampUnits(l, held).subtract(BigInteger.ONE).max(BigInteger.ONE).toString(); render.run(); });
            plus.setOnClickListener(v -> { l.units = clampUnits(l, held).add(BigInteger.ONE).min(held).toString(); render.run(); });
            render.run();
            st.addView(minus); st.addView(qty); st.addView(plus);
            TextView of = new TextView(this); of.setText("  of " + held + " you hold"); of.setTextColor(Design.DIM); of.setTextSize(13f);
            st.addView(of);
            body.addView(st);
        }

        TextView done = button("Done — listed", true);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(20); done.setLayoutParams(dl); done.setPadding(dp(16), dp(13), dp(16), dp(13));
        done.setOnClickListener(v -> {
            if (!priced(l)) { toast("Set a price."); return; }
            l.listed = true;
            pop();
            refreshIfTag("gallery");
            refreshIfCollection(l.tokenid);
        });
        body.addView(done);

        TextView remove = button("Remove listing", false);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(8); remove.setLayoutParams(rl);
        remove.setTextColor(Design.RED);
        remove.setOnClickListener(v -> { l.listed = false; pop(); refreshIfTag("gallery"); refreshIfCollection(l.tokenid); });
        body.addView(remove);

        return col;
    }

    private BigInteger clampUnits(NftListing l, BigInteger held) {
        BigInteger u = wholeUnits(l.units);
        if (u.signum() <= 0) u = held;
        return u.min(held).max(BigInteger.ONE);
    }

    private void showFullArt(NftListing l) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(Identicon.forToken(identiconKey(l), dp(320)));
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) ImageLoader.loadFull(this, artUrl, iv, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(iv);
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("Close", null).show();
    }

    // ==== SCREEN 2b · Collection piece grid ====

    private List<NftListing> piecesOf(StateCollection col) {
        List<NftListing> out = new ArrayList<>();
        for (Map.Entry<String, NftListing> e : listings.entrySet())
            if (e.getKey().startsWith(col.tokenid + "#") && e.getValue().stateIdx > 0) out.add(e.getValue());
        out.sort((a, b) -> Integer.compare(a.stateIdx, b.stateIdx));
        return out;
    }

    private NftListing bundleOf(StateCollection col) { return listings.get(col.tokenid + "#ALL"); }

    private View buildCollectionGrid(final StateCollection col) {
        LinearLayout root = column();
        root.setTag("collection:" + col.tokenid);
        root.addView(header(col.meta.name, true));

        List<NftListing> pieces = piecesOf(col);
        NftListing bundle = bundleOf(col);
        int listed = 0;
        for (NftListing l : pieces) if (l.listed) listed++;
        boolean bundleListed = bundle != null && bundle.listed;

        // Collection identity row: the standard top-side token icon next to the stats — the pieces
        // below carry their own stamped artwork.
        LinearLayout idRow = new LinearLayout(this); idRow.setOrientation(LinearLayout.HORIZONTAL); idRow.setGravity(Gravity.CENTER_VERTICAL);
        idRow.setPadding(dp(16), dp(8), dp(16), 0);
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(Design.roundBg(this, Design.SURFACE2, 10)); icon.setClipToOutline(true);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(54), dp(54)); il.rightMargin = dp(10); icon.setLayoutParams(il);
        icon.setImageBitmap(Identicon.forToken(col.tokenid, dp(54)));
        String iconUrl = IconResolver.resolve(col.meta.icon);
        if (iconUrl != null && !iconUrl.isEmpty()) ImageLoader.loadOver(this, iconUrl, icon, null);
        idRow.addView(icon);
        LinearLayout stats = new LinearLayout(this); stats.setOrientation(LinearLayout.VERTICAL);
        TextView sub = new TextView(this);
        sub.setText(pieces.size() + " of " + col.meta.size + " pieces in your wallet");
        sub.setTextColor(Design.TEXT); sub.setTextSize(13f);
        stats.addView(sub);
        TextView sub2 = new TextView(this);
        sub2.setText(bundleListed ? "complete collection listed · " + Util.tidyAmount(bundle.price) + " " + currencyLabel()
                : listed + " pieces listed · " + listedItems().size() + "/" + MAX_PRODUCTS + " shop slots");
        sub2.setTextColor(bundleListed || listed > 0 ? Design.ACCENT : Design.DIM); sub2.setTextSize(12f);
        stats.addView(sub2);
        idRow.addView(stats);
        root.addView(idRow);

        if (col.unstampedCount > 0) root.addView(hint(col.unstampedCount + " unstamped — not listable until stamped."));
        if (col.legacy) root.addView(hint("Legacy contract: the creator can still rewrite piece state."));
        if (col.tooLong) root.addView(hint("This collection's coin list is too large for this node to return here."));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(8), dp(16), 0);
        TextView priceAll = button("Price each…", false);
        priceAll.setOnClickListener(v -> priceAllDialog(col));
        LinearLayout.LayoutParams palp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        palp.rightMargin = dp(8); priceAll.setLayoutParams(palp);
        actions.addView(priceAll);
        TextView sellAll = button(bundleListed ? "Complete collection · edit" : "Sell complete collection…", bundleListed);
        sellAll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sellAll.setOnClickListener(v -> bundleDialog(col));
        actions.addView(sellAll);
        root.addView(actions);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setPadding(dp(10), dp(4), dp(10), dp(10));
        rv.setClipToPadding(false);
        rv.setAdapter(new GalleryAdapter(pieces, true));
        root.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int totalListed = listed + (bundleListed ? 1 : 0);
        TextView back = button(totalListed == 0 ? "Nothing listed yet" : "Done · " + (bundleListed ? "complete collection listed" : listed + " listed") + "  →", totalListed > 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(dp(16), dp(8), dp(16), dp(14)); back.setLayoutParams(bl);
        back.setPadding(dp(16), dp(13), dp(16), dp(13));
        back.setOnClickListener(v -> pop());
        root.addView(back);
        return root;
    }

    /** One price for every listable piece you hold, in edition order, up to the shop cap.
     *  Mutually exclusive with the complete-collection bundle (double-selling guard). */
    private void priceAllDialog(final StateCollection col) {
        final EditText in = new EditText(this);
        in.setHint("Price per piece (" + currencyLabel() + ")");
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setTextColor(Design.TEXT); in.setHintTextColor(Design.DIM2);
        new AlertDialog.Builder(this)
                .setTitle("Price each owned piece")
                .setView(in)
                .setPositiveButton("List them", (d, w) -> {
                    String p = in.getText().toString().trim();
                    try { if (new BigDecimal(p).signum() <= 0) { toast("Set a price above 0."); return; } }
                    catch (Exception e) { toast("Set a price above 0."); return; }
                    unlistBundle(col);
                    int added = 0; boolean capped = false;
                    for (NftListing l : piecesOf(col)) {
                        if (l.listed) { l.price = p; continue; }
                        if (listedItems().size() >= MAX_PRODUCTS) { capped = true; break; }
                        l.listed = true; l.price = p; added++;
                    }
                    toast(capped ? "Listed " + added + " — shop cap is " + MAX_PRODUCTS : "Listed " + added + " pieces at " + p + " " + currencyLabel());
                    refreshIfCollection(col.tokenid);
                    refreshIfTag("gallery");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void unlistBundle(StateCollection col) {
        NftListing b = bundleOf(col);
        if (b != null && b.listed) { b.listed = false; toast("Complete-collection listing removed."); }
    }
    private void unlistPieces(StateCollection col) {
        int n = 0;
        for (NftListing l : piecesOf(col)) if (l.listed) { l.listed = false; n++; }
        if (n > 0) toast(n + " piece listing" + (n == 1 ? "" : "s") + " removed.");
    }

    /** Sell every piece you hold as ONE product. The Inbox delivers them piece by piece — each is
     *  its own on-chain transaction. Mutually exclusive with per-piece listings. */
    private void bundleDialog(final StateCollection col) {
        if (col.ownedCoins.isEmpty()) { toast("You hold no listable pieces of this collection."); return; }
        NftListing existing = bundleOf(col);
        final EditText in = new EditText(this);
        in.setHint("Price for all " + col.ownedCoins.size() + " pieces (" + currencyLabel() + ")");
        if (existing != null && !existing.price.isEmpty()) in.setText(existing.price);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setTextColor(Design.TEXT); in.setHintTextColor(Design.DIM2);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Sell the complete collection")
                .setMessage("One listing for every piece you hold (" + col.ownedCoins.size() + "). The buyer receives them one by one — each piece is its own on-chain transfer.")
                .setView(in)
                .setPositiveButton(existing != null && existing.listed ? "Update price" : "List it", (d, w) -> {
                    String p = in.getText().toString().trim();
                    try { if (new BigDecimal(p).signum() <= 0) { toast("Set a price above 0."); return; } }
                    catch (Exception e) { toast("Set a price above 0."); return; }
                    unlistPieces(col);
                    NftListing l = listings.get(col.tokenid + "#ALL");
                    if (l == null) {
                        if (listedItems().size() >= MAX_PRODUCTS) { toast("Shop is full (" + MAX_PRODUCTS + ")."); return; }
                        l = new NftListing();
                        l.name = col.meta.name + " — complete collection";
                        l.description = "Every piece the seller holds, delivered to your wallet one by one.";
                        l.units = "1";
                        listings.put(col.tokenid + "#ALL", l);
                    }
                    l.tokenid = col.tokenid; l.bundle = true; l.snMeta = col.meta;
                    l.listed = true; l.price = p;
                    refreshIfCollection(col.tokenid);
                    refreshIfTag("gallery");
                })
                .setNegativeButton("Cancel", null);
        if (existing != null && existing.listed) {
            b.setNeutralButton("Unlist", (d, w) -> {
                unlistBundle(col);
                refreshIfCollection(col.tokenid);
                refreshIfTag("gallery");
            });
        }
        b.show();
    }

    // ==== SCREEN 3 · Shop details ====

    private View buildShopDetails() {
        LinearLayout col = column();
        col.setTag("shop");
        col.addView(header("Your shop", true));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(sectionLabel("Shop name"));
        body.addView(field(shopName, "e.g. Zebra Gallery", s -> shopName = s));

        body.addView(sectionLabel("About"));
        EditText ab = field(about, "One line about your gallery (optional)", s -> about = s);
        ab.setMinLines(2); body.addView(ab);

        body.addView(sectionLabel("Pays in"));
        LinearLayout cur = new LinearLayout(this); cur.setOrientation(LinearLayout.HORIZONTAL);
        cur.addView(seg("Minima", currency.equals("Minima"), () -> { currency = "Minima"; tokenid = CommsTransport.MINIMA; refreshTop(buildShopDetails()); }));
        cur.addView(seg("mxUSDT", currency.equals("USDT"), () -> { currency = "USDT"; tokenid = CommsTransport.USDT; refreshTop(buildShopDetails()); }));
        body.addView(cur);

        int n = listedItems().size();
        TextView info = new TextView(this);
        info.setText(n + (n == 1 ? " listing" : " listings") + " · delivery is on-chain, automatic after payment");
        info.setTextColor(Design.DIM); info.setTextSize(12.5f); info.setPadding(0, dp(18), 0, 0);
        body.addView(info);

        TextView preview = button("Preview shop  →", true);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(16); preview.setLayoutParams(pl); preview.setPadding(dp(16), dp(13), dp(16), dp(13));
        preview.setOnClickListener(v -> {
            if (shopName.trim().isEmpty()) { toast("Give your shop a name."); return; }
            push(buildPreview());
        });
        body.addView(preview);

        return col;
    }

    // ==== SCREEN 4 · Preview (buyer's view) + export ====

    private View buildPreview() {
        LinearLayout col = column();
        col.setTag("preview");
        col.addView(header("Preview — buyer's view", true));

        // collection header, exactly the miniMall treatment
        LinearLayout ch = new LinearLayout(this); ch.setOrientation(LinearLayout.VERTICAL);
        ch.setPadding(dp(16), dp(10), dp(16), dp(4));
        TextView sn = new TextView(this); sn.setText(shopName.trim());
        sn.setTextColor(Design.TEXT); sn.setTextSize(19f); sn.setTypeface(null, Typeface.BOLD);
        ch.addView(sn);
        TextView sub = new TextView(this); sub.setText("NFT SHOP · pays in " + currencyLabel());
        sub.setTextColor(Design.ACCENT); sub.setTextSize(11.5f); sub.setTypeface(null, Typeface.BOLD);
        sub.setPadding(0, dp(2), 0, 0);
        ch.addView(sub);
        if (!about.trim().isEmpty()) {
            TextView abt = new TextView(this); abt.setText(about.trim());
            abt.setTextColor(Design.DIM); abt.setTextSize(13f); abt.setPadding(0, dp(4), 0, 0);
            ch.addView(abt);
        }
        col.addView(ch);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setPadding(dp(10), dp(4), dp(10), dp(10));
        rv.setClipToPadding(false);
        rv.setAdapter(new GalleryAdapter(listedItems(), false));
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView export = button(exporting ? "Preparing images…" : "Export & share .shop", true);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.setMargins(dp(16), dp(8), dp(16), dp(14)); export.setLayoutParams(el);
        export.setPadding(dp(16), dp(13), dp(16), dp(13));
        export.setOnClickListener(v -> exportShop());
        col.addView(export);

        return col;
    }

    // ---- export (.shop schema v2) ----

    private void exportShop() {
        if (exporting) return;
        if (shopName.trim().isEmpty()) { toast("Give your shop a name."); return; }
        if (!cardReady()) { toast("Connect your node so the shop key can be set."); return; }
        final List<NftListing> sel = listedItems();
        if (sel.isEmpty()) { toast("List at least one NFT."); return; }
        for (NftListing l : sel) {
            if (!priced(l)) { toast("Set a price for " + displayName(l) + "."); return; }
            // A piece must still be in the wallet, stamped and cleanly replayable at export time.
            if (l.stateIdx > 0 && (l.coinRaw == null || StateNft.stamped(l.coinRaw) == null || !StateNft.replayableState(l.coinRaw))) {
                toast(displayName(l) + " is no longer sellable — refresh the gallery."); return;
            }
            if (l.bundle) {
                StateCollection c = collections.get(l.tokenid);
                if (c == null || c.ownedCoins.isEmpty()) { toast(displayName(l) + " has no pieces left — refresh the gallery."); return; }
            }
        }
        exporting = true;
        refreshTop(buildPreview());
        io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("schema", 3);
                o.put("shopType", "nft");
                o.put("shopName", shopName.trim()); o.put("shopId", slug(shopName));
                if (!about.trim().isEmpty()) o.put("about", about.trim());
                o.put("vendorPublicId", vendorPublicId); o.put("vendorAddress", vendorAddress);
                o.put("currency", currency); o.put("tokenid", tokenid);
                // compat shipping row: older miniMall builds need one; new builds hide it for NFT shops
                JSONArray sh = new JSONArray();
                JSONObject so = new JSONObject();
                so.put("id", "digital"); so.put("label", "Digital / on-chain delivery"); so.put("fee", "0");
                sh.put(so);
                o.put("shipping", sh);
                JSONArray ps = new JSONArray();
                int i = 0;
                for (NftListing l : sel) {
                    if (l.cachedB64.isEmpty()) l.cachedB64 = resolveImageB64(l);
                    JSONObject po = new JSONObject();
                    po.put("id", "p" + (i++));
                    po.put("name", displayName(l));
                    po.put("description", l.description == null ? "" : l.description.trim());
                    po.put("mode", "units"); po.put("price", l.price.trim());
                    po.put("image", l.cachedB64);
                    po.put("nftTokenId", l.tokenid);
                    if (l.bundle) {
                        // Complete collection: every piece the seller holds, delivered one by one
                        StateCollection c = collections.get(l.tokenid);
                        po.put("maxUnits", 1);
                        po.put("nftBundle", 1);
                        po.put("nftPieces", c == null ? 0 : c.ownedCoins.size());
                        po.put("nftCollection", l.snMeta.name);
                        po.put("nftSupply", l.snMeta.size);
                        String bExt = l.snMeta.externalUrl;
                        if (bExt != null && (bExt.startsWith("http://") || bExt.startsWith("https://"))) po.put("nftExternalUrl", bExt);
                    } else if (l.stateIdx > 0) {
                        // StateNFT piece: unique, identified on-chain by state port 0
                        po.put("maxUnits", 1);
                        po.put("nftStateIdx", l.stateIdx);
                        po.put("nftCollection", l.snMeta.name);
                        po.put("nftSupply", l.snMeta.size);
                        String pExt = l.snMeta.externalUrl;
                        if (pExt != null && (pExt.startsWith("http://") || pExt.startsWith("https://"))) po.put("nftExternalUrl", pExt);
                    } else {
                        BigInteger held = wholeUnits(l.bal.sendable);
                        po.put("maxUnits", clampUnits(l, held).intValue());
                        po.put("nftSupply", wholeUnits(l.bal.total).intValue());
                        String tkr = l.bal.meta.ticker;
                        if (tkr != null && !tkr.isEmpty()) po.put("nftTicker", tkr);
                        String ext = l.bal.meta.externalUrl;
                        if (ext != null && (ext.startsWith("http://") || ext.startsWith("https://"))) po.put("nftExternalUrl", ext);
                    }
                    ps.put(po);
                }
                o.put("products", ps);

                // Collection directory: name/size/top-side icon per referenced collection, so the
                // buyer app can show the standard token icon alongside the stamped piece art.
                java.util.LinkedHashSet<String> colIds = new java.util.LinkedHashSet<>();
                for (NftListing l : sel) if (l.snMeta != null && (l.stateIdx > 0 || l.bundle)) colIds.add(l.tokenid);
                if (!colIds.isEmpty()) {
                    JSONArray cols = new JSONArray();
                    for (String tid : colIds) {
                        StateCollection c = collections.get(tid);
                        if (c == null) continue;
                        JSONObject co = new JSONObject();
                        co.put("tokenid", tid);
                        co.put("name", c.meta.name);
                        co.put("size", c.meta.size);
                        String iconB64 = resolveIconB64(c);
                        if (!iconB64.isEmpty()) co.put("icon", iconB64);
                        cols.put(co);
                    }
                    o.put("nftCollections", cols);
                }

                File dir = new File(getCacheDir(), "shops"); dir.mkdirs();
                File f = new File(dir, slug(shopName) + ".shop");
                try (FileWriter w = new FileWriter(f)) { w.write(o.toString()); }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                ui.post(() -> {
                    exporting = false;
                    refreshTop(buildPreview());
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/octet-stream");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, shopName.trim() + " — NFT shop");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share your shop"));
                });
            } catch (Exception e) {
                ui.post(() -> { exporting = false; refreshTop(buildPreview()); toast("Export failed: " + e.getMessage()); });
            }
        });
    }

    /** The collection's top-side token icon → small bare base64 JPEG (identicon fallback). */
    private String resolveIconB64(StateCollection col) {
        Bitmap bmp = null;
        String iconUrl = IconResolver.resolve(col.meta.icon);
        if (iconUrl != null && !iconUrl.isEmpty()) bmp = ImageLoader.decodeSync(iconUrl, 320);
        if (bmp == null) bmp = Identicon.forToken(col.tokenid, 256);
        byte[] jpeg = Images.compressToFit(bmp, 24000);
        return jpeg == null ? "" : android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
    }

    /** The listing's art → bare base64 JPEG for the .shop image field (identicon when unfetchable).
     *  StateNFT pieces use their state-embedded art (SVG rasterizes via androidsvg); plain NFTs the
     *  token icon. Runs on the io executor: url-mode/http icons may hit the network. */
    private String resolveImageB64(NftListing l) {
        Bitmap bmp = null;
        String artUrl = artUrlOf(l);
        if (!artUrl.isEmpty()) bmp = ImageLoader.decodeSync(artUrl, 900);
        if (bmp == null) bmp = Identicon.forToken(identiconKey(l), 512);
        byte[] jpeg = Images.compressToFit(bmp, 90000);   // ~90KB, same budget as miniMall Studio photos
        if (jpeg == null) return "";
        return android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
    }

    // ---- helpers ----

    private static String slug(String s) {
        String r = (s == null ? "shop" : s).replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        return r.isEmpty() ? "shop" : r;
    }
    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return c;
    }
    private LinearLayout header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (back) {
            TextView bk = new TextView(this); bk.setText("‹"); bk.setTextColor(Design.TEXT); bk.setTextSize(22f);
            bk.setPadding(0, 0, dp(14), 0);
            bk.setOnClickListener(v -> pop());
            h.addView(bk);
        }
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.TEXT); t.setTextSize(18f); t.setTypeface(null, Typeface.BOLD);
        h.addView(t); return h;
    }
    private TextView sectionLabel(String s) {
        TextView t = new TextView(this); t.setText(s.toUpperCase()); t.setTextColor(Design.DIM2); t.setTextSize(11f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); return t;
    }
    private TextView hint(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(Design.DIM); t.setTextSize(12.5f);
        t.setPadding(dp(16), 0, dp(16), dp(4)); return t;
    }
    private View spacerWeight() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }
    private EditText field(String value, String hintText, java.util.function.Consumer<String> onChange) {
        EditText e = new EditText(this); e.setText(value == null ? "" : value); e.setHint(hintText); e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT); e.setTextSize(14f); e.setBackground(Design.roundBg(this, Design.SURFACE2, 10));
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(4); e.setLayoutParams(lp);
        e.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { onChange.accept(s.toString()); }
        });
        return e;
    }
    private TextView seg(String text, boolean on, Runnable onClick) {
        TextView b = new TextView(this); b.setText(text); b.setGravity(Gravity.CENTER); b.setTextSize(14f);
        b.setTextColor(on ? Design.ON_ACCENT : Design.DIM); b.setBackground(Design.roundBg(this, on ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp.rightMargin = dp(8); b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run()); return b;
    }
    private TextView button(String text, boolean active) {
        TextView b = new TextView(this); b.setText(text); b.setTextSize(14f); b.setGravity(Gravity.CENTER);
        b.setTextColor(active ? Design.ON_ACCENT : Design.TEXT); b.setBackground(Design.roundBg(this, active ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(16), dp(11), dp(16), dp(11)); return b;
    }
    private TextView roundBtn(String s) {
        TextView b = new TextView(this); b.setText(s); b.setGravity(Gravity.CENTER); b.setTextSize(18f);
        b.setTextColor(Design.TEXT); b.setBackground(Design.roundBg(this, Design.SURFACE2, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.rightMargin = dp(4); lp.leftMargin = dp(4); b.setLayoutParams(lp);
        return b;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
