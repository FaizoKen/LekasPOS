package com.lekaspos.domain.staff

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.staff.PinLockout
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.queryList
import com.lekaspos.data.staff.RoleDao
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.domain.StaffSession
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.testing.TestGraph
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaffTest {

    private val name = "test-${UUID.randomUUID()}.db"
    private lateinit var graph: AppGraph
    private val owner = Seed.Ids.STAFF_OWNER

    @Before
    fun setUp() {
        graph = TestGraph.create(name)
        runBlocking { graph.staff.load() }
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    private fun refused(reason: ActionRefused.Reason, block: suspend () -> Unit) {
        val e = assertFailsWith<ActionRefused> { runBlocking { block() } }
        assertEquals(reason, e.reason)
    }

    private fun audits(action: Int) = runBlocking { graph.db().read { AuditDao.countByAction(it, action) } }

    /** Owner PIN 2468, a manager (PIN 5555) and a cashier (PIN 1111). */
    private suspend fun team(): Pair<Long, Long> {
        graph.staffAdmin.setPin(owner, "2468")
        val manager = graph.staffAdmin.save(null, "Ah Kow", Seed.Ids.ROLE_MANAGER, true)
        graph.staffAdmin.setPin(manager, "5555")
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        return manager to cashier
    }

    @Test
    fun theOwnersFirstPinTurnsLoginOnAndTheSignInSurvivesARestart() = runBlocking {
        val s = graph.staff
        assertFalse(s.state.value.loginRequired)
        assertEquals(owner, s.staffId)
        assertEquals(Perm.ALL, s.perms) // no PINs yet: the owner, as before Phase 4

        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        refused(ActionRefused.Reason.OWNER_PIN_FIRST) { graph.staffAdmin.setPin(cashier, "1111") }

        val first = graph.staffAdmin.setPin(owner, "2468")
        assertNotNull(first.recoveryCode) // shown once
        assertTrue(s.state.value.loginRequired)
        assertEquals(owner, s.state.value.current?.id) // the owner who typed it stays signed in
        assertNull(graph.staffAdmin.setPin(cashier, "1111").recoveryCode)

        val stored = graph.db().read { StaffDao.get(it, cashier) }?.pin
        assertTrue(stored != null && stored.startsWith("p2:") && !stored.contains("1111"))

        s.lock()
        assertTrue(s.state.value.locked)
        assertEquals(0L, s.perms)
        val wrong = s.signIn(cashier, "0000")
        assertIs<StaffSession.Check.WrongPin>(wrong)
        assertEquals(PinLockout.FREE_TRIES - 1, wrong.triesLeft)
        assertIs<StaffSession.Check.Ok>(s.signIn(cashier, "1111"))
        assertEquals(cashier, s.staffId)
        assertEquals(Perm.DEFAULT_CASHIER, s.perms)
        assertEquals(1L, audits(AuditAction.SIGN_IN))

        // Process restart: the cashier is still signed in (crash-safe till).
        TestGraph.close(graph)
        graph = TestGraph.reopen(name)
        graph.staff.load()
        assertEquals(cashier, graph.staff.state.value.current?.id)
        assertEquals(Perm.DEFAULT_CASHIER, graph.staff.perms)
    }

    @Test
    fun managersApproveWhatCashiersMayNotDo() = runBlocking {
        val (manager, cashier) = team()
        val gate = graph.permissions
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        assertFalse(gate.allowed(Perm.VOID))
        assertNull(gate.actorOrNull(Perm.VOID))
        refused(ActionRefused.Reason.NOT_ALLOWED) { gate.actor(Perm.VOID) }

        assertEquals(StaffSession.Check.NotAllowed, gate.approve(cashier, "1111", Perm.VOID).first) // no VOID in her role
        assertIs<StaffSession.Check.WrongPin>(gate.approve(manager, "5556", Perm.VOID).first)
        val approval = assertNotNull(gate.approve(manager, "5555", Perm.VOID).second)
        assertEquals(manager, gate.actor(Perm.VOID, approval).approvedBy)
        assertEquals(cashier, gate.actor(Perm.VOID, approval).staffId)
        assertNull(gate.actorOrNull(Perm.REFUND, approval)) // an approval covers one permission

        // A screen holding the approval allows it until the screen closes or the till locks.
        val token = gate.elevate(approval)
        assertTrue(gate.allowed(Perm.VOID))
        assertEquals(manager, gate.actor(Perm.VOID).approvedBy)
        gate.release(token)
        assertFalse(gate.allowed(Perm.VOID))
        val again = gate.elevate(approval)
        graph.staff.lock()
        assertFalse(gate.holds(again))
    }

    @Test
    fun wrongPinsMakeTheTillWait() = runBlocking {
        val (_, cashier) = team()
        val s = graph.staff
        s.lock()
        repeat(PinLockout.FREE_TRIES - 1) { assertIs<StaffSession.Check.WrongPin>(s.signIn(cashier, "9999")) }
        val fifth = s.signIn(cashier, "9999")
        assertIs<StaffSession.Check.WrongPin>(fifth)
        assertEquals(PinLockout.FIRST_WAIT_MS, fifth.waitMs)
        assertIs<StaffSession.Check.Wait>(s.signIn(cashier, "1111")) // even the right PIN waits
        assertEquals(1L, audits(AuditAction.PIN_LOCKOUT))
        assertTrue(s.waitMs() > 0L)

        // Time passes (the last failure moves 31 s back): the right PIN works and resets the count.
        graph.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, StaffSession.KEY_LAST_FAIL, (System.currentTimeMillis() - 31_000L).toString()) }
        assertIs<StaffSession.Check.Ok>(s.signIn(cashier, "1111"))
        assertEquals("0", graph.db().read { Meta.get(it, StaffSession.KEY_FAILS) })
    }

    @Test
    fun anOwnerWhoCanSignInAlwaysRemains() = runBlocking {
        val (manager, _) = team()
        val admin = graph.staffAdmin
        val ownerRow = assertNotNull(graph.db().read { StaffDao.get(it, owner) })
        refused(ActionRefused.Reason.LAST_OWNER) { admin.setPin(owner, null) }
        refused(ActionRefused.Reason.LAST_OWNER) { admin.save(ownerRow, ownerRow.name, Seed.Ids.ROLE_OWNER, active = false) }
        refused(ActionRefused.Reason.LAST_OWNER) { admin.save(ownerRow, ownerRow.name, Seed.Ids.ROLE_MANAGER, active = true) }
        refused(ActionRefused.Reason.NOT_ALLOWED) { admin.delete(owner) } // not yourself

        // With a second owner the first may step down.
        val managerRow = assertNotNull(graph.db().read { StaffDao.get(it, manager) })
        admin.save(managerRow, managerRow.name, Seed.Ids.ROLE_OWNER, true)
        admin.setPin(owner, null)
        assertFalse(assertNotNull(graph.db().read { StaffDao.get(it, owner) }).hasPin)
        assertTrue(graph.staff.state.value.loginRequired)
        assertTrue(audits(AuditAction.STAFF_CHANGE) >= 6L)
    }

    @Test
    fun theRecoveryCodeResetsAForgottenOwnerPin() = runBlocking {
        val code = assertNotNull(graph.staffAdmin.setPin(owner, "2468").recoveryCode)
        graph.staff.lock()
        assertFalse(graph.staffAdmin.recover(owner, "AAAA-BBBB-CCCC", "1357"))
        assertTrue(graph.staff.state.value.locked)
        assertTrue(graph.staffAdmin.recover(owner, code.lowercase().replace("-", " "), "1357"))
        assertEquals(owner, graph.staff.state.value.current?.id)
        graph.staff.lock()
        assertIs<StaffSession.Check.WrongPin>(graph.staff.signIn(owner, "2468")) // the old PIN is gone
        assertIs<StaffSession.Check.Ok>(graph.staff.signIn(owner, "1357"))
        assertEquals(1L, audits(AuditAction.OWNER_PIN_RESET))
        // A new code replaces the old one.
        val next = graph.staffAdmin.newRecoveryCode()
        graph.staff.lock()
        assertFalse(graph.staffAdmin.recover(owner, code, "2222"))
        assertTrue(graph.staffAdmin.recover(owner, next, "2222"))
    }

    @Test
    fun rolesStartWithDefaultsAndSyncTheirEdits() = runBlocking {
        val db = graph.db()
        db.syncEnabled = true
        val roles = db.read { RoleDao.list(it) }.associateBy { it.id }
        assertEquals(Perm.ALL, roles.getValue(Seed.Ids.ROLE_OWNER).effective)
        assertEquals(Perm.DEFAULT_MANAGER, roles.getValue(Seed.Ids.ROLE_MANAGER).perms)
        assertEquals(Perm.DEFAULT_CASHIER, roles.getValue(Seed.Ids.ROLE_CASHIER).perms)

        val stock = graph.staffAdmin.saveRole(null, "Stock clerk", Perm.MANAGE_STOCK or Perm.REPRINT)
        val clerk = graph.staffAdmin.save(null, "Raju", stock, true)
        refused(ActionRefused.Reason.SEED_ROLE) { graph.staffAdmin.deleteRole(roles.getValue(Seed.Ids.ROLE_CASHIER)) }
        val stockRole = assertNotNull(db.read { RoleDao.get(it, stock) })
        refused(ActionRefused.Reason.ROLE_IN_USE) { graph.staffAdmin.deleteRole(stockRole) }
        graph.staffAdmin.save(assertNotNull(db.read { StaffDao.get(it, clerk) }), "Raju", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.deleteRole(stockRole)
        assertNull(db.read { RoleDao.list(it) }.firstOrNull { it.id == stock })

        // Owner role permissions cannot be narrowed.
        graph.staffAdmin.saveRole(roles.getValue(Seed.Ids.ROLE_OWNER), "Boss", 0L)
        val boss = assertNotNull(db.read { RoleDao.get(it, Seed.Ids.ROLE_OWNER) })
        assertEquals("Boss", boss.name)
        assertEquals(Perm.ALL, boss.effective)

        val events = db.read { r -> r.queryList("SELECT entity FROM outbox") { it.getInt(0) } }
        assertTrue(events.count { it == Entity.ROLE } >= 3)
        assertTrue(events.count { it == Entity.STAFF } >= 2)
        assertTrue(events.count { it == Entity.AUDIT } >= 4)
    }
}
