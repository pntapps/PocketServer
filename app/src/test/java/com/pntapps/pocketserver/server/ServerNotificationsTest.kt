package com.pntapps.pocketserver.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerNotificationsTest {
    @Test fun coreListsCollapseToRanges() {
        assertEquals("0-3", ServerNotifications.coreRanges("0,1,2,3"))
        assertEquals("0-3,6", ServerNotifications.coreRanges("0,1,2,3,6"))
        assertEquals("0,2,4", ServerNotifications.coreRanges("4,0,2"))
        assertEquals("5", ServerNotifications.coreRanges("5"))
        assertEquals("junk", ServerNotifications.coreRanges("junk"))
    }
}
