package de.flolang.matchyourgame.manager.lobby;

public final class DropdownPagination {
    private DropdownPagination() {}

    public static Page page(int itemCount, int requestedPage) {
        int pageSize = itemCount <= 25 ? 25 : 23;
        int pageCount = Math.max(1, (itemCount + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int from = Math.min(page * pageSize, itemCount);
        int to = Math.min(from + pageSize, itemCount);
        return new Page(page, pageCount, from, to, page > 0, page < pageCount - 1);
    }

    public record Page(int index, int count, int from, int to, boolean previous, boolean next) {
        public int componentCount() { return to - from + (previous ? 1 : 0) + (next ? 1 : 0); }
    }
}
