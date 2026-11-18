package com.wanderwildwood.kondate.ui

import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun aShareOpensOverTheListOrOverWhatIsBeingTypedIn() {
        val page = Screen.Reading("https://example.org/pie")
        assertEquals(listOf(Screen.List, page), ScreenStack.opening(listOf(Screen.List, Screen.Recipe("a1"), Screen.Cook("a1")), page))
        val typing = listOf(Screen.List, Screen.Recipe("a1"), Screen.Edit("a1"))
        assertEquals(typing + page, ScreenStack.opening(typing, page))
        val pasting = listOf(Screen.List, Screen.Paste)
        val shared = Screen.Edit(null, Recipe(id = "", name = "Shared"), 3)
        assertEquals(pasting + shared, ScreenStack.opening(pasting, shared))
        assertEquals(typing + page, ScreenStack.opening(typing + page, page))
    }

    @Test fun twoEditorsKeepTheirStateApart() {
        assertNotEquals(ScreenStack.key(2, Screen.Edit("a1")), ScreenStack.key(3, Screen.Edit(null, Recipe(id = "", name = "Shared"))))
        assertNotEquals(ScreenStack.key(1, Screen.Edit(null)), ScreenStack.key(2, Screen.Edit(null)))
        assertEquals(ScreenStack.key(1, Screen.Edit("a1")), ScreenStack.key(1, Screen.Edit("a1")))
    }

    @Test fun anUnknownLineIsDropped() {
        assertNull(ScreenStack.read("""{"at":"elsewhere"}"""))
        assertNull(ScreenStack.read("nonsense"))
    }
}
