package de.flolang.matchyourgame.manager.review;

public final class RatingFormatter {
    private static final char FULL = '★';
    private static final char EMPTY = '☆';

    private RatingFormatter() {}

    public static String stars(double rating) {
        double clamped = Math.max(0, Math.min(5, rating));
        int fullStars = (int) Math.round(clamped);
        StringBuilder result = new StringBuilder(5);
        result.append(String.valueOf(FULL).repeat(fullStars));
        result.append(String.valueOf(EMPTY).repeat(5 - fullStars));
        return result.toString();
    }
}
