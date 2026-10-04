package com.lekaspos.ui.staff

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.Perm
import com.lekaspos.data.staff.Role
import com.lekaspos.data.staff.Staff
import com.lekaspos.domain.StaffSession
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.visible
import com.lekaspos.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [action] (a staff or role change). Refused as owner-only while the signed-in person is not
 * an owner, an owner approves this one change with their PIN and it runs again with that approval:
 * owners and what roles may do are an owner's business, and an approval of the whole Staff screen
 * no longer counts for them (2026-10 review).
 */
private fun ScreenActivity.asOwnerIfRefused(busy: (Boolean) -> Unit = {}, action: suspend (com.lekaspos.domain.Approval?) -> Unit) {
    busy(true)
    launchUi {
        try {
            action(null)
        } catch (e: com.lekaspos.domain.sale.ActionRefused) {
            val s = graph.staff.state.value
            if (e.reason != com.lekaspos.domain.sale.ActionRefused.Reason.OWNER_ONLY || !s.loginRequired || s.current?.isOwner == true) throw e
            ApprovalDialog.show(this@asOwnerIfRefused, graph, scope, Perm.MANAGE_STAFF, onCancel = null, ownersOnly = true) { a ->
                busy(true)
                launchUi {
                    try {
                        action(a)
                    } finally {
                        busy(false)
                    }
                }
            }
        } finally {
            busy(false)
        }
    }
}

/** Settings → Staff: who may use the till, their roles and PINs (D-037). */
class StaffActivity : ScreenActivity() {

    private lateinit var header: TextView
    private lateinit var empty: TextView

    private val adapter = RowAdapter<Staff>(
        bind = { h, s ->
            val parts = listOfNotNull(
                s.roleName,
                if (s.hasPin) getString(R.string.staff_pin_set) else getString(R.string.staff_no_pin),
                if (s.active) null else getString(R.string.staff_inactive),
            )
            h.set(s.name, parts.joinToString(" · "), tag = if (s.id == graph.staff.state.value.current?.id) getString(R.string.staff_you) else null)
        },
        onClick = { startActivity(StaffEditActivity.intent(this, it.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.staff_title), R.layout.list_header) ?: return
        header = v.findViewById(R.id.list_header)
        empty = v.findViewById(R.id.list_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        addAction(R.drawable.ic_add, R.string.staff_add) { startActivity(StaffEditActivity.intent(this, 0L)) }
        lateinit var more: ImageButton
        more = addAction(R.drawable.ic_more, R.string.sell_menu) { showMenu(more) }
        guard(Perm.MANAGE_STAFF)
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        launchUi {
            val staff = graph.staffAdmin.staff()
            adapter.submit(staff)
            empty.visible(staff.isEmpty())
            val s = graph.staff.state.value
            val lock = graph.settings.device.value.autoLockMinutes
            header.text = if (s.loginRequired) {
                getString(R.string.staff_login_on, if (lock == 0) getString(R.string.staff_autolock_never) else getString(R.string.staff_autolock_min, lock))
            } else {
                getString(R.string.staff_login_off)
            }
        }
    }

    private fun showMenu(anchor: android.view.View) {
        val m = PopupMenu(this, anchor)
        val items = listOf(R.string.roles_title, R.string.staff_autolock, R.string.staff_recovery_new, R.string.staff_change_own_pin)
        for ((i, res) in items.withIndex()) m.menu.add(0, res, i, res)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.roles_title -> startActivity(Intent(this, RolesActivity::class.java))
                R.string.staff_autolock -> chooseAutoLock()
                R.string.staff_recovery_new -> newRecoveryCode()
                R.string.staff_change_own_pin -> changeOwnPin(this, graph, scope)
            }
            true
        }
        m.show()
    }

    private fun chooseAutoLock() {
        val minutes = listOf(0, 1, 2, 5, 10, 30)
        val current = minutes.indexOf(graph.settings.device.value.autoLockMinutes).coerceAtLeast(0)
        val labels = minutes.map { if (it == 0) getString(R.string.staff_autolock_never) else getString(R.string.staff_autolock_min, it) }
        Dialogs.choose(this, getString(R.string.staff_autolock), labels, current) { i ->
            launchUi {
                graph.settings.saveDevice(graph.settings.device.value.copy(autoLockMinutes = minutes[i]))
                reload()
            }
        }
    }

    private fun newRecoveryCode() {
        Dialogs.confirm(this, getString(R.string.staff_recovery_new), getString(R.string.staff_recovery_confirm), getString(R.string.ok)) {
            launchUi { showRecoveryCode(this@StaffActivity, graph.staffAdmin.newRecoveryCode()) }
        }
    }
}

/** The signed-in staff member changes their own PIN (old PIN first). */
fun changeOwnPin(a: Activity, graph: AppGraph, scope: CoroutineScope) {
    val me = graph.staff.state.value.current
    if (me == null) {
        Dialogs.message(a, null, a.getString(R.string.staff_login_off))
        return
    }
    askPin(a, a.getString(R.string.staff_change_own_pin), a.getString(R.string.pin_current)) { old, done ->
        done(null)
        askNewPin(a, a.getString(R.string.pin_new_title, me.name)) { pin ->
            scope.launch {
                val text = try {
                    val c = graph.staffAdmin.changeOwnPin(old, pin)
                    if (c is StaffSession.Check.Ok) a.getString(R.string.pin_changed) else checkMessage(a, c)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Changing the PIN failed", e)
                    ScreenActivity.errorText(a, e)
                }
                Toast.makeText(a, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Add or edit one staff member: name, role, active, PIN. */
class StaffEditActivity : ScreenActivity() {

    private var staffId = 0L
    private var before: Staff? = null
    private var roles: List<Role> = emptyList()
    private lateinit var form: Form
    private lateinit var name: EditText
    private lateinit var role: Spinner
    private lateinit var active: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The person just added, when Android ended the app while their PIN was asked: Android starts the
        // screen again with the intent it was first opened with ("Add staff"), so a second save made them
        // twice (setIntent alone does not survive that, 2026-10 review).
        staffId = savedInstanceState?.getLong(STATE_ID, 0L)?.takeIf { it != 0L } ?: intent.getLongExtra(EXTRA_ID, 0L)
        setScreen(getString(if (staffId == 0L) R.string.staff_add else R.string.staff_edit))
        guard(Perm.MANAGE_STAFF)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_ID, staffId)
    }

    override fun onStarted(scope: CoroutineScope) {
        if (::form.isInitialized) return
        launchUi {
            roles = graph.staffAdmin.roles()
            before = if (staffId == 0L) null else graph.staffAdmin.staff().firstOrNull { it.id == staffId }
            if (staffId != 0L && before == null) {
                finish()
                return@launchUi
            }
            build()
        }
    }

    private fun build() {
        val b = before
        form = Form(this)
        name = form.text(getString(R.string.staff_name), b?.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val roleIndex = roles.indexOfFirst { it.id == (b?.roleId ?: DEFAULT_ROLE) }.coerceAtLeast(0)
        role = form.choice(getString(R.string.staff_role), roles.map { it.name }, roleIndex)
        active = form.switch(getString(R.string.staff_active), b?.active ?: true)
        if (b != null) {
            form.section(getString(R.string.staff_pin))
            form.info(getString(if (b.hasPin) R.string.staff_pin_set else R.string.staff_no_pin))
            form.button(getString(if (b.hasPin) R.string.staff_pin_change else R.string.staff_pin_set_button)) { setPin(b) }
            if (b.hasPin) form.button(getString(R.string.staff_pin_remove)) { removePin(b) }
        } else {
            form.info(getString(R.string.staff_pin_after_save))
        }
        form.button(getString(R.string.save), primary = true) { save() }
        if (b != null) form.button(getString(R.string.delete)) { delete(b) }
        content.removeAllViews()
        content.addView(form.view)
    }

    /** A save is running: a second tap on Save made the new staff member twice (2026-10 review). */
    private var saving = false

    private fun save() {
        if (saving) return
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            name.error = getString(R.string.product_error_name)
            return
        }
        val r = roles.getOrNull(role.selectedItemPosition) ?: return
        asOwnerIfRefused({ saving = it }) { approval ->
            val id = graph.staffAdmin.save(before, n, r.id, active.isChecked, approval)
            if (before == null) {
                // A new staff member needs a PIN to sign in: ask for it right away.
                staffId = id
                // Android may end the app while the PIN is asked: the screen comes back editing this
                // person, not "Add staff" again (a second save made them twice, 2026-10 review).
                intent = intent.putExtra(EXTRA_ID, id)
                val all = graph.staffAdmin.staff()
                val created = all.firstOrNull { it.id == id }
                before = created
                when {
                    created == null -> finish()
                    // Their PIN would be refused until an owner has one: said before it is typed, not
                    // after (both PIN entries were thrown away, 2026-10 review).
                    !created.isOwner && all.none { it.isOwner && it.canSignIn } ->
                        Dialogs.message(this, null, getString(R.string.error_owner_pin_first)).setOnDismissListener { finish() }
                    // The owner's approval of this save also covers the PIN that follows (asked twice).
                    else -> setPin(created, approval)
                }
            } else {
                finish()
            }
        }
    }

    private fun setPin(s: Staff, carried: com.lekaspos.domain.Approval? = null) {
        askNewPin(this, getString(R.string.pin_new_title, s.name)) { pin ->
            asOwnerIfRefused { approval ->
                val result = graph.staffAdmin.setPin(s.id, pin, approval ?: carried)
                toast(R.string.pin_changed)
                val code = result.recoveryCode
                if (code != null) {
                    showRecoveryCode(this@StaffEditActivity, code).setOnDismissListener { finish() }
                } else {
                    finish()
                }
            }
        }
    }

    private fun removePin(s: Staff) {
        Dialogs.confirm(this, getString(R.string.staff_pin_remove), getString(R.string.staff_pin_remove_confirm, s.name), getString(R.string.staff_pin_remove)) {
            asOwnerIfRefused { approval ->
                graph.staffAdmin.setPin(s.id, null, approval)
                finish()
            }
        }
    }

    private fun delete(s: Staff) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.staff_delete_confirm, s.name), getString(R.string.delete)) {
            asOwnerIfRefused { approval ->
                graph.staffAdmin.delete(s.id, approval)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "staff_id"
        private const val STATE_ID = "staff.id"
        private const val DEFAULT_ROLE = 3L // cashier

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, StaffEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

/** Roles and what each may do. */
class RolesActivity : ScreenActivity() {

    private val adapter = RowAdapter<Role>(
        bind = { h, r ->
            val n = if (r.isOwner) Perm.LIST.size else Perm.LIST.count { Perm.has(r.perms, it) }
            h.set(r.name, resources.getQuantityString(R.plurals.role_perm_count, n, n))
        },
        onClick = { startActivity(RoleEditActivity.intent(this, it.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.roles_title), R.layout.list_plain) ?: return
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        addAction(R.drawable.ic_add, R.string.role_add) { startActivity(RoleEditActivity.intent(this, 0L)) }
        guard(Perm.MANAGE_STAFF)
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi { adapter.submit(graph.staffAdmin.roles()) }
    }
}

/** A role's name and permissions (the owner role always has every permission). */
class RoleEditActivity : ScreenActivity() {

    private var roleId = 0L
    private var before: Role? = null
    private lateinit var form: Form
    private lateinit var name: EditText
    private val switches = LinkedHashMap<Long, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roleId = intent.getLongExtra(EXTRA_ID, 0L)
        setScreen(getString(if (roleId == 0L) R.string.role_add else R.string.role_edit))
        guard(Perm.MANAGE_STAFF)
    }

    override fun onStarted(scope: CoroutineScope) {
        if (::form.isInitialized) return
        launchUi {
            before = if (roleId == 0L) null else graph.staffAdmin.roles().firstOrNull { it.id == roleId }
            if (roleId != 0L && before == null) {
                finish()
                return@launchUi
            }
            build()
        }
    }

    private fun build() {
        val b = before
        form = Form(this)
        name = form.text(getString(R.string.role_name), b?.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        form.section(getString(R.string.role_perms))
        if (b?.isOwner == true) form.info(getString(R.string.role_owner_all))
        for (p in Perm.LIST) {
            val sw = form.switch(getString(permLabel(p)), b?.let { Perm.has(it.effective, p) } ?: false)
            sw.isEnabled = b?.isOwner != true
            switches[p] = sw
        }
        form.button(getString(R.string.save), primary = true) { save() }
        if (b != null && !b.isOwner && b.sysRole == 0) form.button(getString(R.string.delete)) { delete(b) }
        content.removeAllViews()
        content.addView(form.view)
    }

    /** A save is running: a second tap on Save made a new role twice (2026-10 review). */
    private var saving = false

    private fun save() {
        if (saving) return
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            name.error = getString(R.string.product_error_name)
            return
        }
        var perms = 0L
        for ((p, sw) in switches) if (sw.isChecked) perms = perms or p
        asOwnerIfRefused({ saving = it }) { approval ->
            graph.staffAdmin.saveRole(before, n, perms, approval)
            finish()
        }
    }

    private fun delete(r: Role) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.role_delete_confirm, r.name), getString(R.string.delete)) {
            launchUi {
                graph.staffAdmin.deleteRole(r)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "role_id"

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, RoleEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
