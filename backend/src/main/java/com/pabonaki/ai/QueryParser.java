package com.pabonaki.ai;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a natural-language shopping query into: cleaned text, optional budget bounds, a "wants cheap" hint and
 * keyword tokens. Deliberately rule-based so it is deterministic, free and testable.
 */
public final class QueryParser {
    private QueryParser() {}

    public static final int MAX_QUERY_CHARS = 200;

    public record Parsed(String text, String keywordText, List<String> tokens, Double minPrice, Double maxPrice, boolean wantsCheap) {}

    private static final String CUR = "(?:৳|bdt|tk\\.?|taka)";
    private static final String NUM = "(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k)?";
    private static final Pattern BETWEEN = Pattern.compile("(?:between|from)\\s*" + CUR + "?\\s*" + NUM + "\\s*" + CUR + "?\\s*(?:and|to|-)\\s*" + CUR + "?\\s*" + NUM + "\\s*" + CUR + "?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX = Pattern.compile("(?:under|below|less than|within|up to|upto|max(?:imum)?|budget(?: of)?|not more than|cheaper than|at most)\\s*" + CUR + "?\\s*" + NUM + "\\s*" + CUR + "?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN = Pattern.compile("(?:over|above|more than|at least|minimum|min)\\s*" + CUR + "?\\s*" + NUM + "\\s*" + CUR + "?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("(?:" + CUR + "\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k)?)|(?:(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k)?\\s*" + CUR + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHEAP = Pattern.compile("\\b(affordable|cheap|inexpensive|low[- ]?cost|low[- ]?price|budget[- ]friendly|economical)\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP = Set.of("a", "an", "the", "for", "with", "and", "or", "to", "of", "in", "on", "at", "my", "me", "i",
            "is", "are", "be", "that", "this", "it", "who", "whom", "someone", "somebody", "likes", "like", "good", "great", "best", "nice",
            "suitable", "want", "need", "looking", "find", "show", "get", "buy", "some", "any", "something", "gift", "under", "below", "over", "above");

    /** Removes control characters, collapses whitespace and caps the length. */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\p{Cntrl}&&[^\\s]]", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_QUERY_CHARS ? s.substring(0, MAX_QUERY_CHARS).trim() : s;
    }

    private static double num(String digits, String k) {
        double v = Double.parseDouble(digits.replace(",", ""));
        return k != null && !k.isEmpty() ? v * 1000 : v;
    }

    public static Parsed parse(String raw) {
        String text = sanitize(raw);
        String work = text;
        Double min = null, max = null;

        Matcher m = BETWEEN.matcher(work);
        if (m.find()) {
            double a = num(m.group(1), m.group(2)), b = num(m.group(3), m.group(4));
            min = Math.min(a, b); max = Math.max(a, b);
            work = m.replaceFirst(" ");
        }
        m = MAX.matcher(work);
        if (max == null && m.find()) { max = num(m.group(1), m.group(2)); work = m.replaceFirst(" "); }
        m = MIN.matcher(work);
        if (min == null && m.find()) { min = num(m.group(1), m.group(2)); work = m.replaceFirst(" "); }
        m = BARE.matcher(work);
        if (max == null && min == null && m.find()) {
            max = m.group(1) != null ? num(m.group(1), m.group(2)) : num(m.group(3), m.group(4));
            work = m.replaceFirst(" ");
        }
        boolean cheap = CHEAP.matcher(text).find();

        List<String> tokens = new ArrayList<>();
        for (String t : work.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() < 2 || STOP.contains(t) || CHEAP.matcher(t).matches()) continue;
            if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1); // crude plural stem
            if (!tokens.contains(t)) tokens.add(t);
        }
        if (max != null && max <= 0) max = null;
        return new Parsed(text, String.join(" ", tokens), List.copyOf(tokens), min, max, cheap);
    }
}
