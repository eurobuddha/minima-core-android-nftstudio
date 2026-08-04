package com.eurobuddha.merchnftstudio;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;

import java.util.ArrayList;
import java.util.List;

/** A shop catalog + vendor card, loaded from an imported `.shop` bundle (JSON). */
public final class Catalog {

    public String shopName = "Shop", shopId = "", vendorPublicId = "", vendorAddress = "", currency = "Minima", tokenid = "0x00";
    public final List<Shipping> shipping = new ArrayList<>();
    public final List<Product> products = new ArrayList<>();

    public static class Product {
        public String id, name, description = "", mode = "units", price = "0", image = "";
        public int maxUnits = 10;
        /** Tokenid of the NFT being sold (additive field; older consumers ignore it). */
        public String nftTokenId = "";
    }
    public static class Shipping { public String id = "", label = "", fee = "0"; }

    /** Parse a `.shop` bundle (the JSON the studio produces). */
    public static Catalog fromJson(String json) {
        Catalog c = new Catalog();
        try {
            JSONObject o = new JSONObject(json);
            c.shopName = o.optString("shopName", "Shop");
            c.shopId = o.optString("shopId", "");
            c.vendorPublicId = o.optString("vendorPublicId", "");
            c.vendorAddress = o.optString("vendorAddress", "");
            c.currency = o.optString("currency", "Minima");
            c.tokenid = o.optString("tokenid", "0x00");
            JSONArray sh = o.optJSONArray("shipping");
            if (sh != null) for (int i = 0; i < sh.length(); i++) {
                JSONObject s = sh.optJSONObject(i); if (s == null) continue;
                Shipping x = new Shipping(); x.id = s.optString("id"); x.label = s.optString("label"); x.fee = s.optString("fee", "0");
                c.shipping.add(x);
            }
            JSONArray ps = o.optJSONArray("products");
            if (ps != null) for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i); if (p == null) continue;
                Product x = new Product();
                x.id = p.optString("id", "p" + i); x.name = p.optString("name", "Item");
                x.description = p.optString("description", ""); x.mode = p.optString("mode", "units");
                x.price = p.optString("price", "0"); x.maxUnits = p.optInt("maxUnits", 10);
                x.image = p.optString("image", "");
                x.nftTokenId = p.optString("nftTokenId", "");
                c.products.add(x);
            }
        } catch (Exception ignored) { /* keep defaults */ }
        return c;
    }

    /** A well-formed bundle has a valid vendor key and at least one product. */
    public boolean valid() {
        return vendorPublicId != null && !vendorPublicId.contains("PLACEHOLDER")
                && CommsIdentity.isValidPublicId(vendorPublicId) && !products.isEmpty();
    }

    /** Kept for compatibility with the old single-shop call sites. */
    public boolean configured() { return valid(); }
}
