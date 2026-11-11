package dev.firetvlite.iptv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedSourceFocusTest {
    @Test fun activatingTheBottomRowWaitsForBothActiveSourceAndReordering() {
        val request = SavedSourceFocusRequest.Activate(30)
        val oldOrder = (1L..30L).toList()
        val newOrder = listOf(30L) + (1L..29L)
        assertNull(request.target(oldOrder, 1))
        assertNull(request.target(oldOrder, 30))
        assertNull(request.target(newOrder, 1))
        assertEquals(SavedSourceFocusTarget.Row(30), request.target(newOrder, 30))
        assertNull(request.target(emptyList(), null))
    }

    @Test fun deletionWaitsForRemovalThenFocusesTheNextSurvivingNeighbour() {
        val request = SavedSourceFocusRequest.Remove(2, listOf(3, 1))
        assertNull(request.target(listOf(1, 2, 3), 1))
        assertEquals(SavedSourceFocusTarget.Row(3), request.target(listOf(1, 3), 1))
        assertEquals(SavedSourceFocusTarget.Row(1), request.target(listOf(1), 1))
    }

    @Test fun deletingTheBottomOrOnlyRowHasAVisibleFallback() {
        val bottom = SavedSourceFocusRequest.Remove(3, listOf(2, 1))
        assertEquals(SavedSourceFocusTarget.Row(2), bottom.target(listOf(1, 2), 1))
        assertEquals(SavedSourceFocusTarget.Add, bottom.target(emptyList(), null))
        assertEquals(SavedSourceFocusTarget.Row(4), bottom.target(listOf(4), 4))
    }

    @Test fun cancellingDeletionRestoresTheRowWithoutRequiringItToBeActive() {
        assertEquals(SavedSourceFocusTarget.Row(2), SavedSourceFocusRequest.Return(2).target(listOf(1, 2), 1))
        assertEquals(SavedSourceFocusTarget.Add, SavedSourceFocusRequest.Return(2).target(emptyList(), null))
    }
}
