# Glossary — one word per idea, in the app, in Malay, in the code

Use these words in code comments, strings, docs and the user guide, so a shop owner, a translator and an AI mean the
same thing. UI words are the exact string-resource texts (`values/` English, `values-ms/` Bahasa Melayu); check the
resource before quoting it in the guide. "Code" names the class, table or constant.

## Shop and selling

| Idea | English (UI) | Bahasa Melayu (UI) | Code |
|---|---|---|---|
| The app (D-084; the brand is **Lekas**, apps for small businesses — never "LekasPOS" in what people read) | Lekas POS | Lekas POS | `app_name`; package `com.lekaspos.app`, repository `LekasPOS`, assets `LekasPOS.apk`, Drive folder "LekasPOS" (identifiers, kept) |
| A phone/tablet that sells; one install | till | kaunter | device, `device_no`, `meta` identity |
| The shop (all tills sharing one Google account) | shop / store | kedai | store, `store_uuid` |
| The sale being rung up | bill | bil | cart, `CartSession`, `cart`/`cart_line` |
| One row of the bill | line | baris | `CartItem`, `cart_line`, `sale_line` |
| Put a bill aside | Hold / held bills; "Held bills (2)" on an empty bill's Hold button | Tangguh / Bil ditangguh; "Ditangguh (2)" | `CartSession.hold`, `CartStatus.HELD`, `SellActivity.renderHold` |
| Throw the bill away | Clear bill | Kosongkan bil | `CartSession.clear`, `BILL_CANCEL` |
| The product tiles | Items (button; reads Bill while the tiles show on a phone), catalogue | Barang (Bil), katalog | `sell_catalog`, `ProductTileAdapter`, `sell_tool_bill` |
| Best sellers tab | Popular | Laris | `PopularItems` |
| Open the search box (selling screen) | Search | Cari | `btn_search`, `searchOpen` |
| Scan with the phone's camera (tool on two panes) | Camera | Kamera | `btn_camera`, `btn_camera_box`, `CameraScanActivity` |
| A tile's stock beside its price | 5 left / None left | Stok 5 / Habis | `tile_stock`, `tile_stock_none` (not "Baki": that is the change) |
| See a price without selling | Price check | Semak harga | `PriceCheck`, `PriceCheckDialog` |
| Barcode the till does not know | Barcode not found | Kod bar tidak dijumpai | `ScanResult.NotFound` |
| Total of the bill | Total / Total to pay | Jumlah / Jumlah perlu dibayar | `PricedCart.total` |
| Money given back | Change | Baki | `Settlement` change |
| Pay with several methods | Split payment / Part payment | Pecah bayaran / Bayaran sebahagian | `PaymentDialog` split mode |
| 5-sen cash rounding | Rounding | Pembundaran | `currency.cash_step`, `sale.rounding` |
| How a customer pays | payment method | kaedah bayaran (Settings), cara bayaran (Reports) | `payment_method`, `PaymentKind` |
| Pay later | Customer credit | Kredit pelanggan | `PaymentKind.CREDIT`, `credit_entry` |
| Receipt and its copies | Receipt / Print a copy / COPY | Resit / Cetak salinan / SALINAN | `ReceiptBuilder`, `PrintJobKind.REPRINT` |
| Money back for returned items | Refund / return | Bayaran balik / pulangan | `SaleKind.REFUND`, `RefundActivity` |
| Cancel a whole sale | Void / VOIDED | Batalkan / DIBATALKAN | `sale_void`, `SaleStatus.VOIDED` |
| A cashier undoing the sale just made | Void their own last sale (within 5 minutes) | Batalkan jualan terakhir sendiri (dalam 5 minit) | `Perm.VOID_LAST`, `VoidLast` |
| Automatic deal | Promotion; Special price; Buy X get Y free | Promosi; Harga istimewa; Beli X percuma Y | `promotion`, `PromoKind` |
| A deal's further prices ("2 for RM3, 3 for RM4") | More prices; Add another price | Harga lain; Tambah harga lain | `promotion_price`, `PriceTier`, `morePrices` |
| Discount typed by staff (D-079: two Menu tiles) | Bill discount / Item discount; Which item? | Diskaun bil / Diskaun item; Item yang mana? | `Discount`, `BILL_DISCOUNT`, `LINE_DISCOUNT`, `menu_discount_bill`, `menu_discount_item` |
| Price changed on the bill | price changed | harga diubah | `PRICE_OVERRIDE` |
| Customer-facing second display | Customer screen (second screen) | Skrin pelanggan (skrin kedua) | `CustomerDisplay`, `dev.customer_screen` |
| Where the scan beeps play (D-076) | Beep sound: This till's speaker / Customer screen, when connected / Off | Bunyi bip: Pembesar suara kaunter ini / Skrin pelanggan, jika disambung / Mati | `Beeper`, `dev.beep`, `DeviceSettings.BEEP_*` |
| The Menu's groups (D-074) | This bill / Till / Manage shop | Bil ini / Kaunter / Urus kedai | `MenuSheet.group`, `menu_group_*`, `menu_manage` |

## Products and stock

| Idea | English (UI) | Bahasa Melayu (UI) | Code |
|---|---|---|---|
| Thing the shop sells | Product | Produk | `product` |
| Grouping and tab | Category; Move up / Move down (its place on the selling screen, D-086) | Kategori; Naikkan / Turunkan | `category`, `category.sort`, `CategoryDao.reorder` |
| How it is sold | Sold by: Piece / pack, Weight, Price entered at the till | Dijual mengikut: Unit / pek, Berat, Harga dimasukkan di kaunter | `SellMode.UNIT/WEIGHT/OPEN_PRICE` |
| Barcode on a carton | pack barcode | kod bar pek | `product_barcode.pack_qty` |
| Scale's item code | Scale PLU | PLU timbangan | `BarcodeKind.SCALE_PLU` |
| The shop's own code for a product (not a barcode; never call it "item code", that is the PLU) | Your own code (SKU) | Kod sendiri (SKU) | `product.sku` |
| Scale label layout | Label formats | Format label | `scale.templates`, `ScaleTemplate` |
| Hidden from the till | Show in search and catalogue (off), HIDDEN | Papar dalam carian dan katalog, TERSEMBUNYI | `product.active = 0` |
| Picture and colour of a tile | picture, colour | gambar, warna | `product_image`, `product_look`, `TileColor` |
| What a piece costs the shop | Cost price | Harga kos | `product.cost` (moving average) |
| Goods arriving | Receive stock / Delivery | Terima stok / Penghantaran | `purchase`, `MovementKind.RECEIVE` |
| How many of a product the shop has (shelf and storeroom together, one figure; never "Inventory" — renamed in 1.20.0, D-080) | Stock; In stock now | Stok; Stok sekarang | `stock_level`, `menu_inventory`, `inv_title`, `product_stock_label` |
| Stock set or changed by hand | Change stock (Count / Remove / Add); Changed by hand; Written off (never "Adjust stock") | Ubah stok (Kira / Keluarkan / Tambah); Diubah manual; Dihapus kira (never "Laras stok") | `InventoryUi.askChange`, `MovementKind.ADJUST/WASTE`, `stock_count` (no session), `STOCK_WRITE_OFF` |
| Counting shelves | Stock count; Count report; Finish count | Kiraan stok; Laporan kiraan; Tamatkan kiraan | `count_session`, `stock_count` |
| Stock at the start | How many do you have now? (product form); Opening stock (history, CSV) | Berapa banyak yang ada sekarang?; Stok permulaan | `MovementKind.OPENING`, `product_opening_stock` |
| Alert level | Low-stock alert at; Low stock; Running low | Amaran stok rendah pada; Stok rendah; Hampir habis | `product.low_stock` |
| Who you buy from | Supplier | Pembekal | `supplier` |

## People and money

| Idea | English (UI) | Bahasa Melayu (UI) | Code |
|---|---|---|---|
| A person using the till | staff | kakitangan | `staff` |
| What a person may do | Role; Owner / Manager / Cashier; permission | Peranan; Pemilik / Pengurus / Juruwang; kebenaran | `role`, `SysRole`, `Perm` |
| Sign-in screen | Sign in; Lock / switch user | Log masuk; Kunci / tukar pengguna | `LockActivity`, `StaffSession.lock` |
| Auto lock | When the till locks | Bila kaunter dikunci | `dev.lock.minutes`, `dev.lock.after_sale` |
| Owner's way back in | Owner recovery code | Kod pemulihan pemilik | `RecoveryCode`, `owner.recovery` |
| Someone else's PIN for one action | Manager approval | Kelulusan pengurus | `withApproval`, `Approval` |
| A manager helping with one bill | Manager PIN; "Manager: name ✕" | PIN pengurus; "Pengurus: nama ✕" | `PermissionGate.startHelp`, `Perm.TILL_HELP` |
| One person's time at one till | shift; Open shift / Close shift; Start the shift | syif; Buka syif / Tutup syif; Mulakan syif | `shift`, `ShiftService` |
| Change in the drawer at the start | Opening float | Wang apungan | `shift.opening_float` |
| What stays for the next shift | Leave in the drawer | Tinggal dalam laci | meta `shift.left`, `LeftInDrawer` |
| Money in and out of the drawer (D-077; was "Cash in / Cash out / Cash drop") | Add cash / Pay from drawer / Take out extra cash; report: Cash added / Paid from drawer / Extra cash taken out | Tambah wang / Bayar dari laci / Keluarkan wang lebihan; laporan: Wang ditambah / Dibayar dari laci / Wang lebihan diambil | `CashMoveKind` CASH_IN / CASH_OUT / DROP, `MoveWords` |
| Expected vs counted | Expected cash / Counted cash / Over / short | Tunai dijangka / Tunai dikira / Lebih / kurang | `ShiftReport`, `ShiftText` |
| Leaving the till with a shift open | Close the shift? / Close shift / Lock only | Tutup syif? / Tutup syif / Kunci sahaja | `SellActivity.lockTill` |
| The closing-time reminder | Remind to close the shift at / No reminder; Closing time / Not yet | Ingatkan tutup syif pada / Tiada peringatan; Waktu tutup kedai / Belum lagi | `shift.close_at`, `ShiftGuide.closeDue` |
| Another person takes the till | Take over the till? / Count the drawer / Not now | Ambil alih kaunter? / Kira laci / Bukan sekarang | `ShiftService.handover`, `SHIFT_CONTINUED` |
| Customers and debts | Customers and credit (pay later); owes; credit limit | Pelanggan dan kredit (bayar kemudian); hutang; had kredit | `customer`, `credit_entry`, `customer_balance` |
| Paying a debt | Take a repayment | Terima bayaran hutang | `CreditKind.PAYMENT` |
| Record of sensitive actions | Activity log | Log aktiviti | `audit_log`, `AuditAction` |
| What the owner should look at | Staff check; Checks | Semakan staf; Semakan | `StaffCheck`, `Checks` |
| Profit figures | Net sales (without tax); Cost of goods; Gross profit | Jualan bersih (tanpa cukai); Kos barang; Untung kasar | `ReportService.Report` |

## Data and devices

| Idea | English (UI) | Bahasa Melayu (UI) | Code |
|---|---|---|---|
| Copy to Google Drive + keeping tills the same | Google Drive backup; sync | Sandaran Google Drive; segerak | `SyncEngine`, `DriveProvider` |
| Backups on the phone, files, restore | Backup & restore; backup file; Restore | Sandaran; fail sandaran; Pulihkan | `BackupService`, `.lekasbak` |
| Automatic backups made before the data was found damaged, never pruned (D-086) | Before the data was damaged (kept) | Sebelum data rosak (disimpan) | `KeepDamagedDatabase.KEPT`, `backup_reason_kept` |
| Daily copy off the phone without Google | Daily copy to a folder (SD card / USB) | Salinan harian ke folder (kad SD / USB) | `BackupFolder`, `dev.backup_folder*` |
| Data only on this phone | Not backed up | Tiada sandaran | `Protection.State.AT_RISK` |
| Damaged database | Data problem | Masalah data | `Protection.State.DAMAGED`, `KeepDamagedDatabase` |
| Restore modes | Restore this till / Replace the old phone / Add as a new till | Pulihkan kaunter ini / Ganti telefon lama / Tambah sebagai kaunter baharu | `Restore.Mode.REPLACE/NEW_DEVICE` |
| Daily CSV to the owner's Drive | Daily sales report to Google Drive | Laporan jualan harian ke Google Drive | `DailyReportUpload` |
| The shop in a web browser — the web page of every Lekas app (D-082, D-084; never "dashboard", "portal" or "admin") | Lekas Manager; Open my shop with Google Drive; Open a backup file; Update; Forget this shop on this computer | Pengurus Lekas; Buka kedai saya dengan Google Drive; Buka fail sandaran; Kemas kini; Lupakan kedai ini di komputer ini | `site/manager/`, `Store.sync`, `readBackup` |
| Making changes on the web page (D-083) | Sign in / Sign out (with the PIN, as on the till); Edit; "Saving to Google Drive…"; Close without saving your changes?; Keep editing; may not do this; Sign in as someone else | Log masuk / Log keluar; Ubah; "Menyimpan ke Google Drive…"; Tutup tanpa menyimpan perubahan anda?; Teruskan mengubah; tidak dibenarkan berbuat ini; Log masuk sebagai orang lain | `site/manager/js/edit.js`, `editing.js` |
| Printer and drawer | Printer & cash drawer; Printer offline | Pencetak & laci wang; Pencetak luar talian | `PrinterService`, `dev.printer.*` |
| Scanner kinds | keyboard-mode scanner; Serial (SPP) Bluetooth scanner | pengimbas mod papan kekunci; Pengimbas Bluetooth bersiri (SPP) | `ScanBuffer`, `SppScanner` |
| New version | App updates; Update x.y.z; What's new | Kemas kini aplikasi; Kemas kini x.y.z; Apa yang baharu | `AppUpdates`, `UpdateWorker` |
| Reports to the developer | Error reports; Send a report to the developer | Laporan ralat; Hantar laporan kepada pembangun | `ErrorReports`, `relay/` |
| Settings that travel vs stay | (store-wide) / this till | — | `setting` (LWW) vs `meta dev.*` |
| The Settings screen's groups (D-074) | This till / Shop / Staff and cash / Data and backups / Help and app | Kaunter ini / Kedai / Kakitangan dan tunai / Data dan sandaran / Bantuan dan aplikasi | `settings_group_*` |

## Words to avoid in user-facing text

- "Device", "sync event", "outbox", "LWW", "HLC", "tombstone", "permission bit" — say till, change, backup, the later
  change wins, deleted, may do.
- "Cancel" for a sale: the app says **Void** (BM *Batalkan*) for a sale and **Clear bill** (BM *Kosongkan bil*) for the
  open bill; *Batal* is only the Cancel button.
- "Other item": it no longer exists (D-050) — only registered products are sold.
- "Sync" alone in the guide: the screen is called **Google Drive backup**; its buttons still say *Turn on sync*,
  *Sync now*.
