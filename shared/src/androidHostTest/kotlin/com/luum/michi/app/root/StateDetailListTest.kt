package com.luum.michi.app.root

import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.navigation.domain.DetailDestination
import com.luum.michi.app.core.navigation.domain.DetailListKind
import com.luum.michi.app.root.state.BackStep
import com.luum.michi.app.root.state.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stateForLists() = State()

/**
 * The "View all" list is a detail-stack layer of its own: it pushes over its
 * parent page and a single back pops just the list (`Root` then re-binds the
 * parent's holder from [DetailDestination.DetailList.parentRef]).
 */
class StateDetailListTest {

    @Test
    fun aViewAllListPushesOverItsParentAndPopsBackToIt() {
        val state = stateForLists()
        state.openMedia(aniListRef(5))
        state.openDetailList(DetailListKind.MEDIA_RELATED, parentRef = aniListRef(5))

        assertEquals(
            DetailDestination.DetailList(DetailListKind.MEDIA_RELATED, aniListRef(5)),
            state.currentDetail,
        )
        assertEquals(BackStep.CloseDetail, state.nextBackStep())

        assertTrue(state.applyBackStep())
        assertEquals(DetailDestination.Media(aniListRef(5)), state.currentDetail)

        // A second back leaves the detail entirely.
        assertTrue(state.applyBackStep())
        assertNull(state.currentDetail)
    }

    @Test
    fun reopeningTheSameListDoesNotDoublePush() {
        val state = stateForLists()
        state.openMedia(aniListRef(5))
        state.openDetailList(DetailListKind.MEDIA_RELATED, parentRef = aniListRef(5))
        state.openDetailList(DetailListKind.MEDIA_RELATED, parentRef = aniListRef(5))

        assertTrue(state.applyBackStep())
        assertEquals(DetailDestination.Media(aniListRef(5)), state.currentDetail)
    }
}
