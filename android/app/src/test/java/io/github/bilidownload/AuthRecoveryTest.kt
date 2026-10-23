package io.github.bilidownload

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class AuthRecoveryTest {
    @Test fun canceledLoginKeepsOriginalNotice() { assertNull(AuthRecovery.loginMessage(LoginResult(LoginOutcome.Canceled))) }
    @Test fun successfulLoginOffersExplicitRecovery() {
        assertTrue(AuthRecovery.loginMessage(LoginResult(LoginOutcome.Success))!!.contains("重新解析"))
        assertTrue(AuthRecovery.loginMessage(LoginResult(LoginOutcome.Success), true)!!.contains("继续 / 重试"))
        assertEquals("验证失败", AuthRecovery.loginMessage(LoginResult(LoginOutcome.Failed, "验证失败")))
    }
    @Test fun guestRestrictionOffersLogin() { assertTrue(AuthRecovery.needsLogin(BiliErrorKind.RequestRestricted.name, SessionState.Guest)) }
    @Test fun authenticatedRestrictionDoesNotForceLogin() { assertFalse(AuthRecovery.needsLogin(BiliErrorKind.RequestRestricted.name, SessionState.Authenticated)) }
    @Test fun expiredLoginOffersLogin() { assertTrue(AuthRecovery.needsLogin(BiliErrorKind.LoginRequired.name, SessionState.Authenticated)) }
    @Test fun legacyFailureDoesNotOfferUnrelatedLogin() { assertFalse(AuthRecovery.needsLogin(null, SessionState.Guest)) }
    @Test fun retryAfterLoginCanStillBeRestricted() {
        val error = BiliException(AuthRecovery.message(BiliErrorKind.RequestRestricted, SessionState.Authenticated), BiliErrorKind.RequestRestricted, httpStatus = 412)
        assertEquals(412, error.httpStatus); assertNull(error.apiCode); assertFalse(AuthRecovery.needsLogin(error.kind.name, SessionState.Authenticated))
    }
    @Test fun oldRecordsHaveNoRecoveryKind() {
        val old = """{"id":"test","item":{"id":"v"},"quality":80,"state":"Failed","message":"error"}"""
        assertNull(Gson().fromJson(old, DownloadEntry::class.java).failureKind)
    }
}
