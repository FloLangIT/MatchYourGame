package de.flolang.matchyourgame.manager.lobby;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DropdownPaginationTest {
    @Test
    void keepsTwentyFiveItemsOnOnePage() {
        DropdownPagination.Page page = DropdownPagination.page(25, 0);
        assertEquals(25, page.componentCount());
        assertFalse(page.previous());
        assertFalse(page.next());
    }

    @Test
    void reservesNavigationEntriesOnceMoreThanTwentyFiveItemsExist() {
        DropdownPagination.Page first = DropdownPagination.page(26, 0);
        DropdownPagination.Page second = DropdownPagination.page(26, 1);
        assertEquals(24, first.componentCount());
        assertTrue(first.next());
        assertFalse(first.previous());
        assertTrue(second.previous());
        assertFalse(second.next());
        assertTrue(second.componentCount() <= 25);
    }

    @Test
    void middlePagesContainBackAndNextWithoutExceedingDiscordLimit() {
        DropdownPagination.Page middle = DropdownPagination.page(100, 2);
        assertTrue(middle.previous());
        assertTrue(middle.next());
        assertEquals(25, middle.componentCount());
    }
}
