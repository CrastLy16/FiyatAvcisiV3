# Fiyat Avcısı Bulut v3

- **Tek Linkten 7 Sitede Otomatik Arama:** Uygulamadaki `+` butonuyla tek bir ürün linki yapıştırdığında bulut tarayıcısı (`check_prices.py`) ürünün adını/markasını/model kodunu çıkarıp diğer 6 sitede otomatik arama yapar.
- **Birebir Aynı Ürün Doğrulaması:** Barkod/GTIN, tire/bölge eki temizlenmiş model/SKU kodu, varyant kilidi (`Pro/Max/Ultra/Plus/128GB/256GB` vb. çelişki kontrolü) ve bulanık Türkçe başlık benzerliğiyle yalnızca birebir aynı ürünleri karşılaştırmaya alır.
- **Doğrudan Uygulamada Aç & Satın Al:** Bildirimdeki **Satın Al (Uygulamada Aç)** butonuna veya fırsat kartına tıkladığında ürün doğrudan ilgili alışveriş uygulamasında (yüklüyse Trendyol, Hepsiburada, Amazon vb. App Link ile) açılır ve link paylaşılabilir.
