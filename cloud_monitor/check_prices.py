#!/usr/bin/env python3
"""
Bulut Fiyat, Otomatik 7-Site Arama & Birebir Urun Dogrulama Tarayicisi (v3)
- Tek bir urun linki eklendiginde urunu diger 6 sitede otomatik arar
- Birebir ayni urun dogrulamasi: GTIN/EAN + normalize model/SKU + varyant/edition (Pro/Max/Ultra/GB/inc) kilidi + bulanik marka/baslik eslestirme
- 7 sitenin genel firsat/indirim sayfalarini 30 dakikada bir tarar
"""

import json
import re
import time
from pathlib import Path
from typing import Any
from urllib.parse import quote_plus, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent
WATCHLIST_PATH = ROOT / "watchlist.json"
SNAPSHOT_PATH = ROOT / "deals_snapshot.json"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8",
}

DEFAULT_DEAL_FEEDS = [
    ("AMAZON", "Amazon TR Günün Fırsatları", "https://www.amazon.com.tr/gp/goldbox"),
    ("HEPSIBURADA", "Hepsiburada Süper Fiyatlar", "https://www.hepsiburada.com/dv/super-fiyat-super-teklif"),
    ("N11", "N11 En İyi Fiyatlı Ürünler", "https://www.n11.com/kampanyalar/akakce-garantili-en-iyi-fiyatli-urunler"),
    ("TRENDYOL", "Trendyol Ev & Yaşam Fırsatları", "https://www.trendyol.com/butik/liste/12/ev-yasam"),
    ("VATAN", "Vatan Bilgisayar TV & Telefon", "https://www.vatanbilgisayar.com/televizyon/"),
    ("TEKNOSA", "Teknosa Kampanyalar", "https://www.teknosa.com/kampanyalar/"),
    ("MEDIAMARKT", "MediaMarkt Kampanyalar", "https://www.mediamarkt.com.tr/tr/campaign/kampanyalar"),
]

SEARCH_TEMPLATES = {
    "AMAZON": "https://www.amazon.com.tr/s?k={q}",
    "HEPSIBURADA": "https://www.hepsiburada.com/ara?q={q}",
    "N11": "https://www.n11.com/arama?q={q}",
    "TRENDYOL": "https://www.trendyol.com/sr?q={q}",
    "VATAN": "https://www.vatanbilgisayar.com/arama/{q}/",
    "TEKNOSA": "https://www.teknosa.com/arama/?s={q}",
    "MEDIAMARKT": "https://www.mediamarkt.com.tr/tr/search.html?query={q}",
}

MODEL_RE = re.compile(r"\b(?=[A-Z0-9/-]{5,20}\b)(?=.*[A-Z])(?=.*\d)[A-Z0-9/-]+\b")
SPEC_RE = re.compile(r"\b\d+(?:[.,]\d+)?\s?(?:gb|tb|mb|mah|w|watt|lt|l|ml|kg|gr|g|inc|inch|hz|cm|mm|btu)\b")
PRICE_RE = re.compile(r"(\d{1,3}(?:\.\d{3})*(?:,\d{2})?|\d+(?:\.\d{2})?)")

EDITION_TOKENS = {
    "pro", "max", "ultra", "plus", "mini", "lite", "air", "se", "fe", "neo",
    "fold", "flip", "edge", "note", "prime", "slim", "oled", "qled", "oled", "5g", "4g",
}

STOP_WORDS = {
    "ve", "ile", "icin", "yeni", "orijinal", "garantili", "turkiye", "distribitor",
    "resmi", "satis", "fiyat", "en", "ucuz", "kargo", "bedava", "hizli", "teslimat",
    "siyah", "beyaz", "mavi", "gri", "kirmizi", "yesil", "mor", "gumus", "altin",
    "titanyum", "lacivert", "pembe", "sari", "turuncu", "koyu", "acik",
    "amazon", "hepsiburada", "trendyol", "n11", "vatan", "teknosa", "mediamarkt",
    "akilli", "cep", "telefonu", "bilgisayar", "televizyon", "urun",
}

SITE_SELECTORS = {
    "AMAZON": ["span.a-price-whole", "#priceblock_ourprice", ".a-offscreen"],
    "HEPSIBURADA": ["[data-test-id='price-current-price']", "#offering-price"],
    "TRENDYOL": ["span.prc-dsc", "span.prc-slg"],
    "N11": [".newPrice ins", "input#productDisplayPrice"],
    "VATAN": [".product-list__price", ".product-detail-price"],
    "TEKNOSA": [".prc-last", ".pdp-price"],
    "MEDIAMARKT": ["[data-test='branded-price-whole-value']", ".price"],
}


def norm_tr(text: str) -> str:
    return (
        text.lower()
        .replace("ç", "c")
        .replace("ğ", "g")
        .replace("ı", "i")
        .replace("ö", "o")
        .replace("ş", "s")
        .replace("ü", "u")
    )


def detect_site(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    for key, label in [
        ("amazon", "AMAZON"),
        ("hepsiburada", "HEPSIBURADA"),
        ("n11", "N11"),
        ("trendyol", "TRENDYOL"),
        ("vatanbilgisayar", "VATAN"),
        ("teknosa", "TEKNOSA"),
        ("mediamarkt", "MEDIAMARKT"),
    ]:
        if key in host:
            return label
    return "OTHER"


def parse_price(raw: Any) -> float | None:
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        return float(raw) if raw > 0 else None
    m = PRICE_RE.search(str(raw).replace(" ", ""))
    if not m:
        return None
    s = m.group(1)
    if "." in s and "," in s:
        s = s.replace(".", "").replace(",", ".") if s.rfind(",") > s.rfind(".") else s.replace(",", "")
    elif "," in s:
        s = s.replace(",", ".") if len(s.split(",")[-1]) == 2 else s.replace(",", "")
    elif "." in s:
        s = s.replace(".", "") if len(s.split(".")[-1]) == 3 else s
    try:
        val = float(s)
        return val if val > 0 else None
    except ValueError:
        return None


def normalize_model(code: str) -> str:
    c = re.sub(r"[^A-Z0-9]", "", code.upper())
    # Ulke / bolge son eklerini temizle (orn. TU/A, TR, EU)
    for suffix in ("TUA", "TR", "EU"):
        if len(c) > 6 and c.endswith(suffix):
            c = c[: -len(suffix)]
    return c


def extract_model_code(title: str, sku: str = "") -> str:
    clean_sku = sku.strip().upper()
    if 5 <= len(clean_sku) <= 24 and any(ch.isdigit() for ch in clean_sku) and any(ch.isalpha() for ch in clean_sku):
        return clean_sku
    for match in MODEL_RE.findall(title.upper()):
        if not match.endswith(("GB", "TB", "HZ", "W", "MAH", "BTU")):
            return match
    return ""


def char_trigrams(text: str) -> set[str]:
    clean = re.sub(r"[^a-z0-9]", "", norm_tr(text))
    if len(clean) < 3:
        return {clean} if clean else set()
    return {clean[i : i + 3] for i in range(len(clean) - 2)}


def verify_same_product(ref: dict[str, Any], cand: dict[str, Any]) -> tuple[str, int]:
    """Farkli sitelerde isim ve kod farkli olsa bile birebir ayni urun dogrulamasi."""
    ref_title = str(ref.get("title") or "")
    cand_title = str(cand.get("title") or "")
    if not (cand_title or cand.get("gtin") or cand.get("modelCode")):
        return "UNCHECKED", 0

    # 1) Barkod / GTIN kesin eslesmesi
    ref_gtin = re.sub(r"\D", "", str(ref.get("gtin") or ""))
    cand_gtin = re.sub(r"\D", "", str(cand.get("gtin") or ""))
    if len(ref_gtin) >= 8 and len(cand_gtin) >= 8:
        return ("VERIFIED_BARCODE", 100) if ref_gtin == cand_gtin else ("NEEDS_REVIEW", 15)

    # 2) Varyant / edition kilidi (orn. iPhone 16 vs iPhone 16 Pro, 128GB vs 256GB eslesmemeli)
    ref_words = set(re.split(r"[^a-z0-9]+", norm_tr(ref_title)))
    cand_words = set(re.split(r"[^a-z0-9]+", norm_tr(cand_title)))
    ref_ed = ref_words & EDITION_TOKENS
    cand_ed = cand_words & EDITION_TOKENS
    if ref_ed != cand_ed:
        return "NEEDS_REVIEW", 30

    ref_specs = {x.replace(" ", "").replace(",", ".") for x in SPEC_RE.findall(norm_tr(ref_title))}
    cand_specs = {x.replace(" ", "").replace(",", ".") for x in SPEC_RE.findall(norm_tr(cand_title))}
    if ref_specs and cand_specs:
        # Ayni birime sahip farkli sayisal deger varsa (128gb vs 256gb) farkli varyanttir
        if ref_specs != cand_specs and not ref_specs.issubset(cand_specs) and not cand_specs.issubset(ref_specs):
            return "NEEDS_REVIEW", 32

    # 3) Normalize model / SKU kodu eslesmesi (tire/slash/bolge eki farklarini tolere eder)
    ref_model = normalize_model(ref.get("modelCode") or extract_model_code(ref_title))
    cand_model = normalize_model(cand.get("modelCode") or extract_model_code(cand_title))
    if len(ref_model) >= 5 and len(cand_model) >= 5:
        if ref_model == cand_model or ref_model in cand_model or cand_model in ref_model:
            return "VERIFIED_MODEL", 96

    # 4) Token + karakter trigram bulanik benzerligi (isim farkliliklari icin)
    ref_toks = {t for t in ref_words if len(t) >= 2 and t not in STOP_WORDS}
    cand_toks = {t for t in cand_words if len(t) >= 2 and t not in STOP_WORDS}
    if not ref_toks or not cand_toks:
        return "UNCHECKED", 0

    overlap = len(ref_toks & cand_toks)
    jaccard = overlap / len(ref_toks | cand_toks)
    containment = overlap / min(len(ref_toks), len(cand_toks))

    tri_ref = char_trigrams(" ".join(sorted(ref_toks)))
    tri_cand = char_trigrams(" ".join(sorted(cand_toks)))
    tri_sim = (len(tri_ref & tri_cand) / len(tri_ref | tri_cand)) if (tri_ref and tri_cand) else 0.0

    score = int((jaccard * 0.35 + containment * 0.40 + tri_sim * 0.25) * 100)

    if ref.get("brand") and cand.get("brand"):
        if norm_tr(ref["brand"]) == norm_tr(cand["brand"]):
            score = min(98, score + 10)
        else:
            score = max(10, score - 30)

    return ("VERIFIED_HIGH" if score >= 68 else "NEEDS_REVIEW"), max(0, min(100, score))


def build_search_query(ref: dict[str, Any], fallback_name: str) -> str:
    brand = str(ref.get("brand") or "").strip()
    model = str(ref.get("modelCode") or "").strip()
    if brand and model and len(model) >= 5:
        return f"{brand} {model}"
    title = str(ref.get("title") or fallback_name).strip()
    words = [w for w in re.split(r"\s+", title) if len(w) >= 2 and norm_tr(w) not in STOP_WORDS]
    return " ".join(words[:6])


def extract_from_html(html: str, site: str) -> dict[str, Any]:
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.string.strip() if soup.title and soup.title.string else ""
    price = None
    brand = ""
    gtin = ""
    sku = ""

    for script in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(script.string or "")
        except Exception:
            continue
        objs = data if isinstance(data, list) else [data]
        expanded = []
        for obj in objs:
            if isinstance(obj, dict):
                expanded.append(obj)
                if isinstance(obj.get("@graph"), list):
                    expanded.extend([g for g in obj["@graph"] if isinstance(g, dict)])
        for obj in expanded:
            if "Product" not in str(obj.get("@type", "")):
                continue
            title = obj.get("name") or title
            b = obj.get("brand")
            brand = b.get("name", "") if isinstance(b, dict) else str(b or "")
            gtin = str(obj.get("gtin13") or obj.get("gtin") or obj.get("ean") or "")
            sku = str(obj.get("sku") or obj.get("mpn") or "")
            offers = obj.get("offers")
            if isinstance(offers, list) and offers:
                offers = offers[0]
            if isinstance(offers, dict):
                price = parse_price(offers.get("price") or offers.get("lowPrice"))
            if price:
                break
        if price:
            break

    if price is None:
        for attr_key, attr_val in [("property", "product:price:amount"), ("property", "og:price:amount"), ("itemprop", "price")]:
            meta = soup.find("meta", attrs={attr_key: attr_val})
            if meta and meta.get("content"):
                price = parse_price(meta["content"])
                if price:
                    break

    if price is None:
        for sel in SITE_SELECTORS.get(site, ["[itemprop='price']", ".price"]):
            el = soup.select_one(sel)
            if el:
                raw = el.get("value") or el.get_text(" ", strip=True)
                price = parse_price(raw)
                if price:
                    break

    return {
        "title": title.strip(),
        "price": price,
        "brand": brand.strip(),
        "gtin": gtin.strip(),
        "modelCode": extract_model_code(title, sku),
    }


def extract_listing_items(html: str, base_url: str) -> list[dict[str, Any]]:
    soup = BeautifulSoup(html, "html.parser")
    items: list[dict[str, Any]] = []
    seen: set[str] = set()

    for script in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(script.string or "")
        except Exception:
            continue
        objs = data if isinstance(data, list) else [data]
        for obj in objs:
            if not isinstance(obj, dict):
                continue
            elements = obj.get("itemListElement")
            if not isinstance(elements, list):
                continue
            for el in elements:
                if not isinstance(el, dict):
                    continue
                item = el.get("item") if isinstance(el.get("item"), dict) else el
                name = str(item.get("name") or "").strip()
                item_url = urljoin(base_url, str(item.get("url") or "").strip())
                offers = item.get("offers")
                if isinstance(offers, list) and offers:
                    offers = offers[0]
                price = parse_price(offers.get("price") or offers.get("lowPrice")) if isinstance(offers, dict) else None
                high_price = parse_price(offers.get("highPrice")) if isinstance(offers, dict) else None
                if name and item_url.startswith("http") and price and item_url not in seen:
                    seen.add(item_url)
                    items.append({"title": name, "url": item_url, "price": price, "strikePrice": high_price})

    for card in soup.select("article, li, div[class*='product'], div[class*='card'], div[class*='deal'], div[data-component-type='s-search-result']")[:220]:
        link = card.find("a", href=True)
        if not link:
            continue
        item_url = urljoin(base_url, link["href"].strip())
        if not item_url.startswith("http") or item_url in seen:
            continue
        text_all = card.get_text(" ", strip=True)
        prices = [p for p in (parse_price(m.group(1)) for m in PRICE_RE.finditer(text_all)) if p and p >= 25]
        if not prices:
            continue
        del_el = card.find(["del", "s"]) or card.select_one("[class*='old'], [class*='original'], [class*='strike']")
        old_p = parse_price(del_el.get_text(" ", strip=True)) if del_el else None
        new_p = min(prices)
        if old_p and (old_p <= new_p or old_p / new_p > 10):
            old_p = None
        title = (link.get("title") or link.get_text(" ", strip=True) or "").strip()
        if len(title) < 6:
            heading = card.find(["h2", "h3", "h4", "span"])
            title = heading.get_text(" ", strip=True) if heading else ""
        if len(title) >= 6:
            seen.add(item_url)
            items.append({"title": title[:140], "url": item_url, "price": new_p, "strikePrice": old_p})

    return items[:100]


def fetch_html_smart(url: str, pw_browser: Any = None) -> tuple[str | None, str]:
    try:
        resp = requests.get(url, headers=HEADERS, timeout=15)
        if resp.status_code == 200 and len(resp.text) > 1500:
            return resp.text, "HTTP"
    except Exception:
        pass

    if pw_browser is not None:
        try:
            page = pw_browser.new_page(user_agent=HEADERS["User-Agent"], locale="tr-TR")
            page.goto(url, wait_until="domcontentloaded", timeout=25000)
            page.wait_for_timeout(2500)
            html = page.content()
            page.close()
            if html and len(html) > 1500:
                return html, "Chromium"
        except Exception as exc:
            return None, f"Chromium hatasi: {exc}"

    return None, "Erisilemedi / Bot korumasi"


def auto_find_on_other_sites(
    ref: dict[str, Any],
    fallback_name: str,
    existing_sites: set[str],
    enabled_sites: set[str],
    browser: Any,
    prev_offer_prices: dict[str, float],
) -> list[dict[str, Any]]:
    """Tek link girilen urunu diger 6 sitede otomatik arar ve yalnizca birebir eslesenleri ekler."""
    query = build_search_query(ref, fallback_name)
    if len(query) < 3:
        return []

    found_offers: list[dict[str, Any]] = []
    target_sites = [s for s in SEARCH_TEMPLATES.keys() if s not in existing_sites and (not enabled_sites or s in enabled_sites)]

    for site in target_sites:
        search_url = SEARCH_TEMPLATES[site].format(q=quote_plus(query))
        html, mode = fetch_html_smart(search_url, browser)
        if not html:
            continue
        candidates = extract_listing_items(html, search_url)[:12]
        best_match = None
        best_score = 0
        best_status = "UNCHECKED"

        for cand in candidates:
            cand_meta = {
                "title": cand["title"],
                "brand": "",
                "gtin": "",
                "modelCode": extract_model_code(cand["title"]),
            }
            m_status, m_score = verify_same_product(ref, cand_meta)
            if m_status in ("VERIFIED_BARCODE", "VERIFIED_MODEL", "VERIFIED_HIGH") and m_score > best_score:
                best_score = m_score
                best_status = m_status
                best_match = cand

        if best_match is not None and best_score >= 70:
            u = best_match["url"]
            curr_p = float(best_match["price"])
            prev_p = prev_offer_prices.get(u)
            drop_pct = round(((prev_p - curr_p) / prev_p) * 100.0, 1) if (prev_p and curr_p < prev_p) else 0.0
            found_offers.append({
                "site": site,
                "url": u,
                "title": best_match["title"],
                "brand": ref.get("brand", ""),
                "gtin": "",
                "modelCode": extract_model_code(best_match["title"]),
                "price": curr_p,
                "previousPrice": prev_p,
                "dropPercent": drop_pct,
                "status": f"Otomatik bulundu ({mode})",
                "matchStatus": best_status,
                "matchScore": best_score,
            })

    return found_offers


def main() -> None:
    if not WATCHLIST_PATH.exists():
        print("watchlist.json bulunamadi.")
        return

    watchlist = json.loads(WATCHLIST_PATH.read_text(encoding="utf-8"))
    enabled_sites = set(watchlist.get("enabledSites", []))
    global_min_drop = float(watchlist.get("globalMinDropPercent", 20))
    scan_general = bool(watchlist.get("scanGeneralDeals", True))

    prev_offer_prices: dict[str, float] = {}
    prev_cat_prices: dict[str, dict[str, float]] = {}
    if SNAPSHOT_PATH.exists():
        try:
            old = json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
            for p in old.get("products", []):
                for o in p.get("offers", []):
                    if o.get("url") and o.get("price"):
                        prev_offer_prices[o["url"]] = float(o["price"])
            for c in old.get("categories", []):
                if c.get("id") and isinstance(c.get("knownPrices"), dict):
                    prev_cat_prices[c["id"]] = {k: float(v) for k, v in c["knownPrices"].items() if v}
        except Exception:
            pass

    pw_ctx = None
    browser = None
    try:
        from playwright.sync_api import sync_playwright
        pw_ctx = sync_playwright().start()
        browser = pw_ctx.chromium.launch(headless=True)
    except Exception:
        browser = None

    now_ms = int(time.time() * 1000)
    out_products = []
    out_categories = []
    big_deals = []
    seen_deal_urls: set[str] = set()

    try:
        for prod in watchlist.get("products", []):
            threshold = float(prod.get("targetDropPercent", global_min_drop))
            raw_offers = []
            existing_sites: set[str] = set()

            for offer in prod.get("offers", []):
                url = str(offer.get("url") or "").strip()
                if not url:
                    continue
                site = offer.get("site") or detect_site(url)
                existing_sites.add(site)
                if enabled_sites and site != "OTHER" and site not in enabled_sites:
                    continue

                html, mode = fetch_html_smart(url, browser)
                meta = extract_from_html(html, site) if html else {"title": "", "price": None, "brand": "", "gtin": "", "modelCode": ""}
                curr_price = meta.get("price")
                prev_price = prev_offer_prices.get(url)
                drop_pct = round(((prev_price - curr_price) / prev_price) * 100.0, 1) if (prev_price and curr_price and curr_price < prev_price) else 0.0

                raw_offers.append({
                    "site": site,
                    "url": url,
                    "title": meta.get("title", ""),
                    "brand": meta.get("brand", ""),
                    "gtin": meta.get("gtin", ""),
                    "modelCode": meta.get("modelCode", ""),
                    "price": curr_price,
                    "previousPrice": prev_price,
                    "dropPercent": drop_pct,
                    "status": f"Güncel ({mode})" if curr_price else f"Okunamadı ({mode})",
                })

            ref = next((o for o in raw_offers if o.get("title")), None)
            verified_offers = []
            for idx, o in enumerate(raw_offers):
                if idx == 0 and ref is o:
                    m_status, m_score = "REFERENCE", 100
                elif ref is not None:
                    m_status, m_score = verify_same_product(ref, o)
                else:
                    m_status, m_score = "UNCHECKED", 0
                o["matchStatus"] = m_status
                o["matchScore"] = m_score
                verified_offers.append(o)

            # Eksik kalan diger sitelerde urunu otomatik ara ve birebir eslesenleri ekle
            if ref is not None:
                auto_offers = auto_find_on_other_sites(
                    ref=ref,
                    fallback_name=str(prod.get("name") or ""),
                    existing_sites=existing_sites,
                    enabled_sites=enabled_sites,
                    browser=browser,
                    prev_offer_prices=prev_offer_prices,
                )
                verified_offers.extend(auto_offers)

            priced = sorted([o for o in verified_offers if o.get("price")], key=lambda x: x["price"])
            comp_summary = "\n".join(f"• {o['site']}: {o['price']:,.2f} TL" for o in priced)

            for o in verified_offers:
                if o.get("price") and o.get("previousPrice") and o["dropPercent"] >= threshold and o["url"] not in seen_deal_urls:
                    seen_deal_urls.add(o["url"])
                    big_deals.append({
                        "title": prod.get("name") or o.get("title") or o["url"],
                        "site": o["site"],
                        "url": o["url"],
                        "oldPrice": o["previousPrice"],
                        "newPrice": o["price"],
                        "dropPercent": o["dropPercent"],
                        "comparisonSummary": comp_summary,
                        "isFromCategory": False,
                    })

            out_products.append({
                "id": prod.get("id", ""),
                "name": prod.get("name", ""),
                "targetDropPercent": int(threshold),
                "offers": verified_offers,
            })

        feeds_to_scan: list[dict[str, Any]] = list(watchlist.get("categories", []))
        if scan_general:
            for site_code, label, feed_url in DEFAULT_DEAL_FEEDS:
                if not enabled_sites or site_code in enabled_sites:
                    feeds_to_scan.append({
                        "id": f"auto_{site_code.lower()}",
                        "name": label,
                        "site": site_code,
                        "url": feed_url,
                        "minDropPercent": int(global_min_drop),
                    })

        for cat in feeds_to_scan:
            cat_id = str(cat.get("id") or "")
            url = str(cat.get("url") or "").strip()
            site = cat.get("site") or detect_site(url)
            if not url or (enabled_sites and site != "OTHER" and site not in enabled_sites):
                continue

            html, mode = fetch_html_smart(url, browser)
            items = extract_listing_items(html, url) if html else []
            known = dict(prev_cat_prices.get(cat_id, {}))
            threshold = float(cat.get("minDropPercent", global_min_drop))

            for it in items:
                u = it["url"]
                new_p = float(it["price"])
                ref_old = known.get(u) or it.get("strikePrice")
                if ref_old and ref_old > new_p:
                    drop_pct = round(((ref_old - new_p) / ref_old) * 100.0, 1)
                    if drop_pct >= threshold and u not in seen_deal_urls:
                        seen_deal_urls.add(u)
                        big_deals.append({
                            "title": it["title"],
                            "site": site,
                            "url": u,
                            "oldPrice": float(ref_old),
                            "newPrice": new_p,
                            "dropPercent": drop_pct,
                            "comparisonSummary": f"Kaynak: {cat.get('name', '')} ({site})",
                            "isFromCategory": True,
                        })
                known[u] = new_p

            out_categories.append({
                "id": cat_id,
                "name": cat.get("name", ""),
                "site": site,
                "url": url,
                "minDropPercent": int(threshold),
                "scannedCount": len(items),
                "status": f"{len(items)} ürün tarandı ({mode})" if html else f"Taranamadı ({mode})",
                "knownPrices": known,
            })
    finally:
        if browser is not None:
            browser.close()
        if pw_ctx is not None:
            pw_ctx.stop()

    big_deals.sort(key=lambda d: d.get("dropPercent", 0), reverse=True)
    snapshot = {
        "updatedAt": now_ms,
        "products": out_products,
        "categories": out_categories,
        "bigDeals": big_deals[:80],
    }
    SNAPSHOT_PATH.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Tarama tamamlandi: {len(out_products)} urun, {len(out_categories)} kaynak, {len(big_deals)} buyuk indirim.")


if __name__ == "__main__":
    main()
