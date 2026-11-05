package org.comicreader.app;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

final class Series {
    record Issue(String series, BigDecimal number) {}

    private static final Pattern MARKED =
            Pattern.compile(
                    "(?i)(?:#|\\b(?:issue|n[º°o.]|n[uú]m(?:ero)?\\.?|cap[ií]tulo|tomo)\\s*[.:-]?)\\s*(\\d{1,4}(?:\\.\\d{1,2})?)");
    private static final Pattern TOTAL = Pattern.compile("(?i)(\\d{1,4})\\s*(?:of|de)\\s*\\d{1,4}");
    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\p{L}\\d])(\\d{1,4}(?:\\.\\d{1,2})?)(?![\\p{L}\\d])");
    private static final Pattern YEAR = Pattern.compile("(?:\\(|\\[)((?:19|20)\\d{2})(?:\\)|\\])");
    private static final Pattern ANNOTATIONS = Pattern.compile("\\([^)]*\\)|\\[[^]]*\\]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\d]+");

    static Issue parse(String filename) {
        if (filename == null || filename.length() > 1024) return null;
        int dot = filename.lastIndexOf('.');
        String title = ComicFiles.supported(filename) ? filename.substring(0, dot) : filename;
        StringBuilder years = new StringBuilder();
        Matcher year = YEAR.matcher(title);
        while (year.find()) years.append(' ').append(year.group(1));
        String clean = ANNOTATIONS.matcher(title).replaceAll(" ");
        Matcher issue = MARKED.matcher(clean);
        int start = -1, end = -1;
        String number = null;
        if (!issue.find()) issue = TOTAL.matcher(clean);
        else {
            start = issue.start();
            end = issue.end();
            number = issue.group(1);
        }
        if (number == null && issue.find()) {
            start = issue.start();
            end = issue.end();
            number = issue.group(1);
        }
        if (number == null) {
            issue = NUMBER.matcher(clean);
            while (issue.find()) {
                int value = new BigDecimal(issue.group(1)).intValue();
                if (value >= 1900 && value <= 2099) continue;
                start = issue.start();
                end = issue.end();
                number = issue.group(1);
            }
        }
        if (number == null) return null;
        String name = clean.substring(0, start);
        if (name.isBlank()) name = clean.substring(end);
        name = Normalizer.normalize(name + years, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        name = SEPARATORS.matcher(name).replaceAll(" ").trim();
        return new Issue(name, new BigDecimal(number).stripTrailingZeros());
    }

    static <T> T next(
            T current,
            Collection<T> items,
            java.util.function.Function<T, String> title,
            java.util.function.Function<T, String> scope,
            java.util.function.Predicate<T> completed) {
        Issue from = parse(title.apply(current));
        if (from == null) return null;
        T next = null;
        BigDecimal lowest = null;
        boolean ambiguous = false;
        for (T item : items) {
            if (!scope.apply(current).equals(scope.apply(item))) continue;
            Issue candidate = parse(title.apply(item));
            if (candidate == null
                    || !candidate.series.equals(from.series)
                    || candidate.number.compareTo(from.number) <= 0) continue;
            if (completed.test(item)) continue;
            int order = lowest == null ? -1 : candidate.number.compareTo(lowest);
            if (order < 0) {
                next = item;
                lowest = candidate.number;
                ambiguous = false;
            } else if (order == 0 && !item.equals(next)) ambiguous = true;
        }
        return ambiguous ? null : next;
    }
}
