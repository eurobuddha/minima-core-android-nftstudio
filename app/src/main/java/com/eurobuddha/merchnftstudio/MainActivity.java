package com.eurobuddha.merchnftstudio;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** miniMerch NFT Studio — list your own wallet NFTs for sale and export a portable .shop bundle. */
public class MainActivity extends AppCompatActivity {

    private LazySodium ls;
    private NodeApi node;
    private boolean paired = false;
    private String vendorPublicId = "", vendorAddress = "";

    private String shopName = "";
    private String currency = "Minima", tokenid = CommsTransport.MINIMA;
    private final java.util.List<Catalog.Shipping> shipping = new java.util.ArrayList<>();

    /** One row per wallet NFT (0-decimal non-Minima token we hold). Keyed by tokenid; a Refresh
     *  merges fresh balance rows in while PRESERVING the vendor's selection + edits. */
    private final LinkedHashMap<String, NftListing> listings = new LinkedHashMap<>();
    private boolean nftsLoading = false, nftsLoaded = false;
    private boolean exporting = false;

    private static final int MAX_PRODUCTS = 40;   // same cap as the .shop schema / miniMerch Studio

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root, form;
    private TextView cardStatus;

    /** A wallet NFT with the vendor's listing edits layered on top of the token metadata. */
    private static final class NftListing {
        TokenBalance bal;
        boolean selected = false;
        String name = "", description = "";   // prefilled from token metadata, editable
        String price = "";
        String units = "";                    // editions to offer, default = sendable holding
        String cachedB64 = "";                // resolved shop image (bare base64 JPEG), lazily built
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();
        seedDefaults();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);

        buildScreen();
        node = new NodeApi(this, this::onPaired);
    }

    @Override protected void onDestroy() { super.onDestroy(); if (node != null) node.onDestroy(); io.shutdownNow(); }

    private void seedDefaults() {
        if (!shipping.isEmpty()) return;
        // NFTs are delivered on-chain to the buyer's pay address — one digital option, still editable.
        Catalog.Shipping x = new Catalog.Shipping();
        x.id = "digital"; x.label = "Digital / on-chain delivery"; x.fee = "0";
        shipping.add(x);
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
                    if (r != null) { vendorAddress = r.optString("miniaddress", r.optString("address", "")); refreshCardStatus(); }
                }
                @Override public void onError(String m) {}
            });
        }
        if (enabled) loadNfts();
        refreshCardStatus();
    }

    private void deriveCard(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                String pid = CommsIdentity.fromSeed(ls, seed).publicId();
                ui.post(() -> { vendorPublicId = pid; refreshCardStatus(); });
            } catch (Exception e) { ui.post(() -> toast("Identity error: " + e.getMessage())); }
        });
    }

    private boolean cardReady() {
        return CommsIdentity.isValidPublicId(vendorPublicId) && vendorAddress != null && !vendorAddress.isEmpty();
    }

    private void refreshCardStatus() {
        if (cardStatus == null) return;
        if (cardReady()) { cardStatus.setText("✓ Shop key ready (from this node's seed)"); cardStatus.setTextColor(Design.IN); }
        else if (!paired) { cardStatus.setText("Enable miniMerch NFT Studio in Minima Core → Apps."); cardStatus.setTextColor(Design.ACCENT); }
        else { cardStatus.setText("Connecting to your node…"); cardStatus.setTextColor(Design.DIM); }
    }

    // ---- wallet NFTs ----
    private void loadNfts() {
        if (nftsLoading) return;
        nftsLoading = true;
        rebuildForm();
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                nftsLoading = false; nftsLoaded = true;
                mergeBalances(j.optJSONArray("response"));
                rebuildForm();
            }
            @Override public void onError(String m) {
                nftsLoading = false;
                if (!NodeApi.ERR_NOT_ENABLED.equals(m)) toast("Couldn't read wallet: " + m);
                rebuildForm();
            }
        });
    }

    /** Keep every 0-decimal non-Minima token we can currently send; carry existing edits across. */
    private void mergeBalances(JSONArray rows) {
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
            NftListing l = listings.get(t.tokenid);
            if (l == null) {
                l = new NftListing();
                l.name = t.meta.name == null ? "" : t.meta.name;
                l.description = t.meta.description == null ? "" : t.meta.description;
                l.units = held.toString();
            }
            l.bal = t;
            fresh.put(t.tokenid, l);
        }
        listings.clear();
        listings.putAll(fresh);
    }

    /** Whole units in a balance amount string ("3", "3.0"); zero on anything unparseable. */
    private static BigInteger wholeUnits(String amount) {
        try { return new BigDecimal(amount == null ? "0" : amount.trim()).toBigInteger(); }
        catch (Exception e) { return BigInteger.ZERO; }
    }

    private int selectedCount() {
        int n = 0;
        for (NftListing l : listings.values()) if (l.selected) n++;
        return n;
    }

    // ---- the editor form ----
    private void buildScreen() {
        root.removeAllViews();
        root.addView(header("miniMerch NFT Studio"));
        ScrollView sv = new ScrollView(this);
        form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(16), dp(8), dp(16), dp(28));
        sv.addView(form);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rebuildForm();
    }

    private void rebuildForm() {
        if (form == null) return;
        form.removeAllViews();

        cardStatus = new TextView(this); cardStatus.setTextSize(12f); cardStatus.setPadding(0, 0, 0, dp(10));
        form.addView(cardStatus); refreshCardStatus();

        form.addView(sectionLabel("Shop name"));
        form.addView(field(shopName, "e.g. Zebra Gallery", s -> shopName = s));

        form.addView(sectionLabel("Currency"));
        LinearLayout cur = new LinearLayout(this); cur.setOrientation(LinearLayout.HORIZONTAL);
        cur.addView(seg("Minima", currency.equals("Minima"), () -> { currency = "Minima"; tokenid = CommsTransport.MINIMA; rebuildForm(); }));
        cur.addView(seg("mxUSDT", currency.equals("USDT"), () -> { currency = "USDT"; tokenid = CommsTransport.USDT; rebuildForm(); }));
        form.addView(cur);

        form.addView(sectionLabel("Shipping (label + fee)"));
        for (final Catalog.Shipping s : shipping) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            EditText label = field(s.label, "Label", v -> s.label = v); label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            EditText fee = field(s.fee, "0", v -> s.fee = v); fee.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT); fl.leftMargin = dp(8); fee.setLayoutParams(fl);
            row.addView(label); row.addView(fee); form.addView(row);
        }

        // ---- wallet NFTs ----
        LinearLayout nh = new LinearLayout(this); nh.setOrientation(LinearLayout.HORIZONTAL); nh.setGravity(Gravity.CENTER_VERTICAL);
        TextView nl = sectionLabel("Wallet NFTs  (" + listings.size() + ")");
        nl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nh.addView(nl);
        TextView refresh = button(nftsLoading ? "…" : "↻ Refresh", false);
        refresh.setOnClickListener(v -> { if (paired) loadNfts(); else toast("Connect your node first."); });
        nh.addView(refresh);
        form.addView(nh);

        if (!paired) {
            form.addView(hint("Enable this app in Minima Core → Apps to read your wallet."));
        } else if (nftsLoading && listings.isEmpty()) {
            form.addView(hint("Reading your wallet…"));
        } else if (listings.isEmpty() && nftsLoaded) {
            form.addView(hint("No NFTs found in this wallet. NFTs are tokens minted with decimals:0; freshly minted ones appear once confirmed."));
        } else {
            form.addView(hint("Tick the NFTs to sell. Freshly minted tokens appear once confirmed."));
            for (NftListing l : listings.values()) form.addView(nftCard(l));
        }

        TextView export = button(exporting ? "Preparing images…" : "Export shop (.shop)", true);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.topMargin = dp(22); export.setLayoutParams(el); export.setPadding(dp(16), dp(14), dp(16), dp(14));
        export.setOnClickListener(v -> exportShop());
        form.addView(export);
    }

    private View nftCard(final NftListing l) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.roundBg(this, Design.SURFACE, 12)); card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8); card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tick = new TextView(this); tick.setText(l.selected ? "✓" : ""); tick.setGravity(Gravity.CENTER);
        tick.setTextColor(Design.ON_ACCENT); tick.setTextSize(15f); tick.setTypeface(null, Typeface.BOLD);
        tick.setBackground(Design.roundBg(this, l.selected ? Design.ACCENT : Design.SURFACE2, 7));
        LinearLayout.LayoutParams tkl = new LinearLayout.LayoutParams(dp(26), dp(26)); tkl.rightMargin = dp(10); tick.setLayoutParams(tkl);
        top.addView(tick);

        ImageView thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Design.roundBg(this, Design.SURFACE2, 8)); thumb.setClipToOutline(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(54), dp(54)); tl.rightMargin = dp(10); thumb.setLayoutParams(tl);
        thumb.setImageBitmap(Identicon.forToken(l.bal.tokenid, dp(54)));
        if (l.bal.hasIcon()) ImageLoader.loadOver(this, l.bal.meta.iconUrl, thumb, null);
        top.addView(thumb);

        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
        tt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nm = new TextView(this); nm.setText(l.name.isEmpty() ? l.bal.meta.name : l.name);
        nm.setTextColor(Design.TEXT); nm.setTextSize(15f); nm.setTypeface(null, Typeface.BOLD); tt.addView(nm);
        TextView sub = new TextView(this);
        String tkr = l.bal.meta.ticker == null || l.bal.meta.ticker.isEmpty() ? "" : l.bal.meta.ticker + " · ";
        sub.setText(tkr + "you hold " + wholeUnits(l.bal.sendable) + " of " + wholeUnits(l.bal.total));
        sub.setTextColor(Design.DIM); sub.setTextSize(12f); tt.addView(sub);
        top.addView(tt);
        card.addView(top);

        View.OnClickListener toggle = v -> {
            if (!l.selected && selectedCount() >= MAX_PRODUCTS) { toast("A shop holds at most " + MAX_PRODUCTS + " products."); return; }
            l.selected = !l.selected;
            rebuildForm();
        };
        tick.setOnClickListener(toggle);
        top.setOnClickListener(toggle);

        if (l.selected) {
            card.addView(field(l.name, "Listing name", v -> l.name = v));
            card.addView(field(l.description, "Description (optional)", v -> l.description = v));

            LinearLayout pr = new LinearLayout(this); pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
            pr.addView(tag("Price")); EditText price = field(l.price, "0", v -> l.price = v);
            price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            price.setLayoutParams(smLp()); pr.addView(price);
            pr.addView(tag("  Sell")); EditText units = field(l.units, "1", v -> l.units = v);
            units.setInputType(InputType.TYPE_CLASS_NUMBER); units.setLayoutParams(smLp()); pr.addView(units);
            pr.addView(tag("of " + wholeUnits(l.bal.sendable)));
            card.addView(pr);

            TextView tid = new TextView(this); tid.setText(Util.shorten(l.bal.tokenid));
            tid.setTextColor(Design.DIM2); tid.setTextSize(11f); tid.setPadding(0, dp(6), 0, 0);
            card.addView(tid);
        }
        return card;
    }

    // ---- export ----
    private void exportShop() {
        if (exporting) return;
        if (shopName.trim().isEmpty()) { toast("Give your shop a name."); return; }
        if (!cardReady()) { toast("Connect your node so the shop key can be set."); return; }
        final java.util.List<NftListing> sel = new java.util.ArrayList<>();
        for (NftListing l : listings.values()) if (l.selected) sel.add(l);
        if (sel.isEmpty()) { toast("Tick at least one NFT to sell."); return; }
        for (NftListing l : sel) {
            String nm = l.name.trim().isEmpty() ? l.bal.meta.name : l.name.trim();
            try {
                if (new BigDecimal(l.price.trim()).signum() <= 0) { toast("Set a price for " + nm + "."); return; }
            } catch (Exception e) { toast("Set a price for " + nm + "."); return; }
        }
        exporting = true;
        rebuildForm();
        io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("shopName", shopName.trim()); o.put("shopId", slug(shopName));
                o.put("vendorPublicId", vendorPublicId); o.put("vendorAddress", vendorAddress);
                o.put("currency", currency); o.put("tokenid", tokenid);
                JSONArray sh = new JSONArray();
                for (Catalog.Shipping s : shipping) { JSONObject so = new JSONObject(); so.put("id", s.id); so.put("label", s.label); so.put("fee", s.fee == null || s.fee.isEmpty() ? "0" : s.fee); sh.put(so); }
                o.put("shipping", sh);
                JSONArray ps = new JSONArray();
                int i = 0;
                for (NftListing l : sel) {
                    if (l.cachedB64.isEmpty()) l.cachedB64 = resolveImageB64(l);
                    BigInteger held = wholeUnits(l.bal.sendable);
                    BigInteger units = wholeUnits(l.units);
                    if (units.signum() <= 0) units = BigInteger.ONE;
                    if (units.compareTo(held) > 0) units = held;      // never offer more than we can send
                    JSONObject po = new JSONObject();
                    po.put("id", "p" + (i++));
                    po.put("name", l.name.trim().isEmpty() ? l.bal.meta.name : l.name.trim());
                    po.put("description", l.description == null ? "" : l.description.trim());
                    po.put("mode", "units"); po.put("price", l.price.trim());
                    po.put("maxUnits", units.intValue());
                    po.put("image", l.cachedB64);
                    po.put("nftTokenId", l.bal.tokenid);
                    ps.put(po);
                }
                o.put("products", ps);

                File dir = new File(getCacheDir(), "shops"); dir.mkdirs();
                File f = new File(dir, slug(shopName) + ".shop");
                try (FileWriter w = new FileWriter(f)) { w.write(o.toString()); }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                ui.post(() -> {
                    exporting = false;
                    rebuildForm();
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/octet-stream");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, shopName.trim() + " — miniMerch NFT shop");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share your shop"));
                });
            } catch (Exception e) {
                ui.post(() -> { exporting = false; rebuildForm(); toast("Export failed: " + e.getMessage()); });
            }
        });
    }

    /** Token icon → bare base64 JPEG for the .shop image field (identicon when absent/unfetchable).
     *  Runs on the io executor: may hit the network for http(s)/ipfs icon urls. */
    private String resolveImageB64(NftListing l) {
        Bitmap bmp = null;
        if (l.bal.hasIcon()) bmp = ImageLoader.decodeSync(l.bal.meta.iconUrl, 900);
        if (bmp == null) bmp = Identicon.forToken(l.bal.tokenid, 512);
        byte[] jpeg = Images.compressToFit(bmp, 90000);   // ~90KB, same budget as miniMerch Studio photos
        if (jpeg == null) return "";
        return android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
    }

    // ---- helpers ----
    private static String slug(String s) {
        String r = (s == null ? "shop" : s).replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        return r.isEmpty() ? "shop" : r;
    }
    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout header(String title) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.TEXT); t.setTextSize(18f); t.setTypeface(null, Typeface.BOLD);
        h.addView(t); return h;
    }
    private TextView sectionLabel(String s) {
        TextView t = new TextView(this); t.setText(s.toUpperCase()); t.setTextColor(Design.DIM2); t.setTextSize(11f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); return t;
    }
    private TextView hint(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(Design.DIM); t.setTextSize(12f);
        t.setPadding(0, 0, 0, dp(4)); return t;
    }
    private TextView tag(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(Design.DIM); t.setTextSize(13f); return t; }
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
    private LinearLayout.LayoutParams smLp() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT); lp.leftMargin = dp(6); lp.rightMargin = dp(6); return lp; }
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
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
