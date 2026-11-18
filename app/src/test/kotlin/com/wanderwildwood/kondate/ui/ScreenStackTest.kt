package com.wanderwildwood.kondate.ui

import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenStackTest {

    @Test fun everyPlaceComesBack() {
        val draft = Recipe(id = "", name = "Half typed", ingredients = listOf("2 eggs"), steps = listOf(Step("Beat them.")))
        val stack = listOf(
            Screen.List,
            Screen.Recipe("a1"),
            Screen.Cook("a1"),
            Screen.Reading("https://example.org/pie"),
            Screen.Paste,
            Screen.Settings,
            Screen.Nextcloud,
            Screen.Edit(null),
            Screen.Edit("a1"),
            Screen.Edit(null, draft, 7),
        )
        assertEquals(stack, stack.map { ScreenStack.read(ScreenStack.write(it)) })
    }

    @Test fun anUnknownLineIsDropped() {
        assertNull(ScreenStack.read("""{"at":"elsewhere"}"""))
        assertNull(ScreenStack.read("nonsense"))
    }
}
