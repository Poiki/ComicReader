package org.comicreader.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public final class SeriesTest {
    private record Comic(String name, String folder, boolean read) {}

    private Comic next(Comic current, Comic... others) {
        return Series.next(current, List.of(others), Comic::name, Comic::folder, Comic::read);
    }

    @Test
    public void recognizesCommonIssueNamesWithoutConfusingYearsOrVolumes() {
        for (String[] pair :
                new String[][] {
                    {"Batman #001 (2022).cbr", "Batman #002 (2022).cbz"},
                    {"Saga - 01.cbz", "Saga - 02.cbz"},
                    {"001.cbz", "002.cbr"},
                    {"01 - Saga.cbz", "02 - Saga.cbz"},
                    {"Saga v02 001.cbz", "Saga v02 002.cbz"},
                    {"Saga 01 of 06.cbz", "Saga 02 of 06.cbz"},
                    {"Saga núm. 1 - Inicio.cbz", "Saga núm. 2 - Regreso.cbz"},
                    {"Saga #1.5.cbz", "Saga #2.cbz"}
                }) {
            Comic first = new Comic(pair[0], "A", false), second = new Comic(pair[1], "A", false);
            assertEquals(Arrays.toString(pair), second, next(first, second));
        }
        assertNull(Series.parse("An anthology.cbz"));
        assertNull(Series.parse("A history of 2024.cbz"));
        assertEquals(
                Series.parse("El Niño #001.cbz").series(),
                Series.parse("El Nin\u0303o #002.cbz").series());
        assertNotEquals(
                Series.parse("Año #001.cbz").series(), Series.parse("Ano #002.cbz").series());
    }

    @Test
    public void keepsSeriesFolderVolumeAndYearBoundaries() {
        Comic current = new Comic("Saga v01 #1 (2022).cbz", "A", false);
        assertNull(
                next(
                        current,
                        new Comic("Other v01 #2 (2022).cbz", "A", false),
                        new Comic("Saga v01 #2 (2022).cbz", "B", false),
                        new Comic("Saga v02 #2 (2022).cbz", "A", false),
                        new Comic("Saga v01 #2 (2011).cbz", "A", false)));
    }

    @Test
    public void selectsLowestUnreadNumberAndRejectsAmbiguousCopies() {
        Comic first = new Comic("Saga 001.cbz", "A", true),
                third = new Comic("Saga 003.cbz", "A", false);
        assertEquals(
                third,
                next(
                        first,
                        new Comic("Saga 002.cbz", "A", true),
                        new Comic("Saga 010.cbz", "A", false),
                        third));
        assertNull(
                next(
                        first,
                        new Comic("Saga 002.cbz", "A", false),
                        new Comic("Saga 02.cbr", "A", false)));
    }
}
