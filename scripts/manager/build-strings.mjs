// Writes site/manager/js/app-strings.js: the app's own words (English values/, Malay values-ms/) that the
// manager web page shows, so its labels are exactly the till's (CLAUDE.md: labels as in values/).
// Run after changing one of the strings below: node scripts/manager/build-strings.mjs
// `node --test scripts/manager/` fails while app-strings.js is out of date.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const res = join(root, 'app/src/main/res');

/** The app strings the web page uses. */
export const NAMES = [
  // Reports (strings_reports.xml)
  'menu_reports', 'report_today', 'report_yesterday', 'report_this_week', 'report_last_week', 'report_this_month',
  'report_last_month', 'report_this_year', 'report_last_year', 'report_custom', 'report_from', 'report_to', 'report_range',
  'report_summary', 'report_sales_count', 'report_refunds_count', 'report_voids', 'report_discounts', 'report_net_ex_tax',
  'report_tax', 'report_rounding', 'report_total', 'report_cost', 'report_profit', 'report_margin', 'report_average',
  'report_vs_previous', 'report_by_day', 'report_by_week', 'report_by_month', 'report_bucket_value', 'report_payments',
  'report_cashiers', 'report_categories', 'report_no_category', 'report_net_profit', 'report_top_products',
  'report_other_items', 'report_count_label', 'report_qty_label', 'report_stock', 'report_stock_value',
  'report_slow_movers', 'report_slow_header', 'report_slow_empty', 'report_in_stock', 'report_note',
  'export_title', 'export_summary', 'export_daily', 'export_products', 'export_receipts',
  // Staff check (strings_fraud.xml)
  'staff_check_title', 'staff_check_intro', 'staff_check_none', 'check_sales', 'check_after_pay', 'check_cleared',
  'check_removed', 'check_voids', 'check_refunds', 'check_discounts', 'check_drawer', 'check_copies', 'check_cash_out',
  'check_write_off', 'check_continued', 'check_over_short', 'check_float_diff',
  // Sales
  'menu_sales', 'sales_search_hint', 'sales_empty', 'sales_none_found', 'sale_voided', 'sale_refund', 'sale_refunds',
  'refund_of',
  // Products and stock
  'products_title', 'products_search_hint', 'product_hidden', 'product_price', 'product_cost', 'product_category',
  'product_no_category', 'product_sku', 'product_barcodes', 'product_track_stock', 'product_low_stock',
  'product_stock_label', 'product_untracked', 'sell_mode_unit', 'sell_mode_weight', 'sell_mode_open',
  'inv_title', 'inv_low_stock', 'inv_movements', 'inv_purchases', 'inv_low_empty', 'inv_movements_empty',
  'inv_purchases_empty', 'inv_purchase_total', 'inv_stock_now', 'move_receive', 'move_adjust', 'move_counted',
  'move_waste', 'move_return', 'move_transfer_in', 'move_transfer_out', 'move_opening',
  // Shifts
  'menu_shift', 'shift_history', 'shift_history_empty', 'shift_open_tag', 'shift_till', 'shift_opened_by',
  'shift_opened_at', 'shift_float', 'shift_report', 'shift_moves', 'shift_cash_in', 'shift_cash_out', 'shift_drop',
  // Activity log
  'settings_audit', 'audit_empty', 'audit_all', 'audit_filter', 'audit_approved_by', 'audit_other',
  'audit_sale_void', 'audit_refund', 'audit_price_override', 'audit_line_discount', 'audit_bill_discount', 'audit_drawer',
  'audit_reprint', 'audit_product_price', 'audit_product_delete', 'audit_promotion', 'audit_bill_cancel', 'audit_approval',
  'audit_sign_in', 'audit_pin_lockout', 'audit_staff', 'audit_role', 'audit_shift_open', 'audit_shift_close',
  'credit_adjust_title', 'audit_over_limit', 'audit_pin_reset', 'audit_product_import', 'audit_credit_limit',
  'audit_settings', 'audit_credit_payment', 'audit_line_remove', 'audit_line_remove_after_pay',
  'audit_bill_cancel_after_pay', 'audit_write_off', 'audit_shift_continued', 'audit_float_difference',
  'perm_discount', 'perm_price', 'perm_cancel_bill', 'perm_void', 'perm_void_last', 'perm_refund', 'perm_reprint',
  'perm_drawer', 'perm_cash_move', 'perm_shift_report', 'perm_customers', 'perm_credit_sale', 'perm_credit_limit',
  'perm_products', 'perm_stock', 'perm_reports', 'perm_audit', 'perm_settings', 'perm_staff', 'perm_other',
  // Tills and data
  'sync_title', 'sync_account', 'sync_tills_empty', 'sync_till_row', 'customers_title', 'customer_owes',
  'sync_turn_on', 'menu_settings', 'backup_title', 'backup_save_file', 'backup_share',
  'reason_damaged', 'reason_expired', 'reason_lost', 'reason_theft', 'reason_own_use', 'reason_returned', 'reason_found',
  'reason_correction', 'reason_other',
  'customers_owing', 'customers_none_found', 'customer_search_hint', 'customer_limit_line', 'customer_removed_tag', 'credit_empty',
  'credit_charge', 'credit_reversal', 'credit_payment', 'credit_adjust', 'credit_balance_after',
];

/** The text of an Android string resource as the app shows it. */
export function unescape(s) {
  let t = s.trim();
  if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t = t.slice(1, -1);
  return t
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\(['"@?\\])/g, '$1')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function load(dir) {
  const out = {};
  for (const f of readdirSync(dir).filter((x) => x.startsWith('strings') && x.endsWith('.xml'))) {
    const xml = readFileSync(join(dir, f), 'utf8');
    for (const m of xml.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) out[m[1]] = unescape(m[2]);
  }
  return out;
}

export function render() {
  const en = load(join(res, 'values'));
  const ms = load(join(res, 'values-ms'));
  const pick = (all, lang) => {
    const o = {};
    for (const n of NAMES) {
      if (all[n] == null) throw new Error(`string ${n} is missing in ${lang}`);
      o[n] = all[n];
    }
    return o;
  };
  const body = (o) => Object.entries(o).map(([k, v]) => `    ${k}: ${JSON.stringify(v)},`).join('\n');
  return '// Generated by scripts/manager/build-strings.mjs from app/src/main/res/values{,-ms}/strings*.xml — do not edit.\n' +
    '// The app\'s own words, so the web page says exactly what the till says.\n\n' +
    'export const APP_STRINGS = {\n' +
    `  en: {\n${body(pick(en, 'values'))}\n  },\n` +
    `  ms: {\n${body(pick(ms, 'values-ms'))}\n  },\n` +
    '};\n';
}

export const OUT = join(root, 'site/manager/js/app-strings.js');

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  writeFileSync(OUT, render());
  console.log(`site/manager/js/app-strings.js: ${NAMES.length} strings`);
}
