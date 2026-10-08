# Glossary — one word per idea, in the app, in Malay, in the code

Use these words in code comments, strings, docs and the user guide, so a shop owner, a translator and an AI mean the
same thing. UI words are the exact string-resource texts (`values/` English, `values-ms/` Bahasa Melayu); check the
resource before quoting it in the guide. "Code" names the class, table or constant.

## Shop and selling

| Idea | English (UI) | Bahasa Melayu (UI) | Code |
|---|---|---|---|
| A phone/tablet that sells; one install | till | kaunter | device, `device_no`, `meta` identity |
| The shop (all tills sharing one Google account) | shop / store | kedai | store, `store_uuid` |
| The sale being rung up | bill | bil | cart, `CartSession`, `cart`/`cart_line` |
| One row of the bill | line | baris | `CartItem`, `cart_line`, `sale_line` |
| Put a bill aside | Hold / held bills | Tangguh / Bil ditangguh | `CartSession.hold`, `CartStatus.HELD` |
| Throw the bill away | Clear bill | Kosongkan bil | `CartSession.clear`, `BILL_CANCEL` |
| The product tiles | Items (button), catalogue | Barang, katalog | `sell_catalog`, `ProductTileAdapter` |
| Best sellers tab | Popular | Laris | `PopularItems` |
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
| Automatic deal | Promotion; Special price; Buy X get Y free | Promosi; Harga istimewa; Beli X percuma Y | `promotion`, `PromoKind` |
| Discount typed by staff | Discount (bill / item) | Diskaun (bil / item) | `Discount`, `BILL_DISCOUNT`, `LINE_DISCOUNT` |
| Price changed on the bill | price changed | harga diubah | `PRICE_OVERRIDE` |
| Customer-facing second display | Customer screen (second screen) | Skrin pelanggan (skrin kedua) | `CustomerDisplay`, `dev.customer_screen` |

## Products and stock

| Idea | English (UI) | Bahasa Melayu (UI) | Code |
|---|---|---|---|
| Thing the shop sells | Product | Produk | `product` |
| Grouping and tab | Category | Kategori | `category` |
| How it is sold | Sold by: Piece / pack, Weight, Price entered at the till | Dijual mengikut: Unit / pek, Berat, Harga dimasukkan di kaunter | `SellMode.UNIT/WEIGHT/OPEN_PRICE` |
| Barcode on a carton | pack barcode | kod bar pek | `product_barcode.pack_qty` |
| Scale's item code | Scale PLU | PLU timbangan | `BarcodeKind.SCALE_PLU` |
| Scale label layout | Label formats | Format label | `scale.templates`, `ScaleTemplate` |
| Hidden from the till | Show in search and catalogue (off), HIDDEN | Papar dalam carian dan katalog, TERSEMBUNYI | `product.active = 0` |
| Picture and colour of a tile | picture, colour | gambar, warna | `product_image`, `product_look`, `TileColor` |
| What a piece costs the shop | Cost price | Harga kos | `product.cost` (moving average) |
| Goods arriving | Receive stock / Delivery | Terima stok / Penghantaran | `purchase`, `MovementKind.RECEIVE` |
| Stock changed by hand | Adjust stock; Written off | Laras stok; Dihapus kira | `MovementKind.ADJUST/WASTE`, `STOCK_WRITE_OFF` |
| Counting shelves | Stock count; Count report; Finish count | Kiraan stok; Laporan kiraan; Tamatkan kiraan | `count_session`, `stock_count` |
| Stock at the start | Opening stock | Stok permulaan | `MovementKind.OPENING` |
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
| Cash movements | Cash in / Cash out / Cash drop | Wang masuk / Wang keluar / Simpanan wang | `CashMoveKind` |
| Expected vs counted | Expected cash / Counted cash / Over / short | Tunai dijangka / Tunai dikira / Lebih / kurang | `ShiftReport`, `ShiftText` |
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
| Daily copy off the phone without Google | Daily copy to a folder (SD card / USB) | Salinan harian ke folder (kad SD / USB) | `BackupFolder`, `dev.backup_folder*` |
| Data only on this phone | Not backed up | Tiada sandaran | `Protection.State.AT_RISK` |
| Damaged database | Data problem | Masalah data | `Protection.State.DAMAGED`, `KeepDamagedDatabase` |
| Restore modes | Restore this till / Replace the old phone / Add as a new till | Pulihkan kaunter ini / Ganti telefon lama / Tambah sebagai kaunter baharu | `Restore.Mode.REPLACE/NEW_DEVICE` |
| Daily CSV to the owner's Drive | Daily sales report to Google Drive | Laporan jualan harian ke Google Drive | `DailyReportUpload` |
| Printer and drawer | Printer & cash drawer; Printer offline | Pencetak & laci wang; Pencetak luar talian | `PrinterService`, `dev.printer.*` |
| Scanner kinds | keyboard-mode scanner; Serial (SPP) Bluetooth scanner | pengimbas mod papan kekunci; Pengimbas Bluetooth bersiri (SPP) | `ScanBuffer`, `SppScanner` |
| New version | App updates; Update x.y.z; What's new | Kemas kini aplikasi; Kemas kini x.y.z; Apa yang baharu | `AppUpdates`, `UpdateWorker` |
| Reports to the developer | Error reports; Send a report to the developer | Laporan ralat; Hantar laporan kepada pembangun | `ErrorReports`, `relay/` |
| Settings that travel vs stay | (store-wide) / this till | — | `setting` (LWW) vs `meta dev.*` |

## Words to avoid in user-facing text

- "Device", "sync event", "outbox", "LWW", "HLC", "tombstone", "permission bit" — say till, change, backup, the later
  change wins, deleted, may do.
- "Cancel" for a sale: the app says **Void** (BM *Batalkan*) for a sale and **Clear bill** (BM *Kosongkan bil*) for the
  open bill; *Batal* is only the Cancel button.
- "Other item": it no longer exists (D-050) — only registered products are sold.
- "Sync" alone in the guide: the screen is called **Google Drive backup**; its buttons still say *Turn on sync*,
  *Sync now*.
