package io.github.bilidownload
import org.junit.Assert.*
import org.junit.Test
class AccountInfoTest {
    @Test fun authenticatedAccountShowsNameAndLogout() {
        val account=AccountInfo(SessionState.Authenticated,"测试用户")
        assertTrue(account.loggedIn); assertEquals("测试用户",account.label)
    }
    @Test fun restoredAccountDoesNotShowLoginWhileVerifying() { assertTrue(AccountInfo(SessionState.Saved,"用户名").loggedIn) }
    @Test fun guestAndExpiredAccountOfferLogin() {
        assertFalse(AccountInfo(SessionState.Guest).loggedIn); assertEquals("未登录",AccountInfo(SessionState.Guest).label)
        assertFalse(AccountInfo(SessionState.Expired,"旧用户").loggedIn); assertEquals("登录已过期",AccountInfo(SessionState.Expired,"旧用户").label)
    }
}
