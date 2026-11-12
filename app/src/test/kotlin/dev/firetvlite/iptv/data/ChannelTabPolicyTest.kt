package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.library.ChannelTabPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTabPolicyTest {
    @Test fun automaticGroupFocusCannotOverridePendingRowRestoration() {
        assertFalse(ChannelTabPolicy.canSelectGroup(2, null))
        assertFalse(ChannelTabPolicy.canSelectGroup(2, 1))
        assertTrue(ChannelTabPolicy.canSelectGroup(2, 2))
        assertTrue(ChannelTabPolicy.canSelectGroup(null, null))
    }
}
