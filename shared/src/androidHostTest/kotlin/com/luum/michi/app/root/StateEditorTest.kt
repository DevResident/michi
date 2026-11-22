package com.luum.michi.app.root

import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.root.state.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's open/close contract, including the completion shortcut. */
class StateEditorTest {

    @Test
    fun openEditorForCompletionSetsAndClearsTheCompletionOverrides() {
        val state = State()

        state.openEditorForCompletion(id = 42, progress = 12)
        assertTrue(state.isEditorOpen)
        assertEquals(42, state.editorMediaId)
        assertEquals(MediaListStatus.COMPLETED, state.editorInitialStatus)
        assertEquals(12, state.editorInitialProgress)

        state.closeEditor()
        assertFalse(state.isEditorOpen)
        assertNull(state.editorMediaId)
        assertNull(state.editorInitialStatus)
        assertNull(state.editorInitialProgress)
    }

    @Test
    fun aMalOnlySyntheticIdOpensNoEditor() {
        val state = State()

        state.openEditor(-29317)
        assertFalse(state.isEditorOpen)

        state.openEditorForCompletion(id = -29317, progress = 1)
        assertFalse(state.isEditorOpen)
        assertNull(state.editorMediaId)
    }
}
