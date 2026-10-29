package org.comicreader.app;

import java.io.*;
import java.util.*;

public final class ComicFiles {
    public static final long MAX_COMIC = 1024L * 1024 * 1024;
    public static final int MAX_PAGE_BYTES = 64 * 1024 * 1024;
    private ComicFiles() { }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    public static boolean supported(String name) {
        return Set.of("cbz", "zip", "pdf").contains(extension(name));
    }
    public static boolean image(String name) {
        String normalized = name.replace('\\', '/');
        for (String part : normalized.split("/"))
            if (part.startsWith(".") || part.equals("__MACOSX")) return false;
        return Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp").contains(extension(name));
    }

    public static int naturalCompare(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            char x = Character.toLowerCase(left.charAt(a)), y = Character.toLowerCase(right.charAt(b));
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int startA = a, startB = b;
                while (a < left.length() && left.charAt(a) >= '0' && left.charAt(a) <= '9') a++;
                while (b < right.length() && right.charAt(b) >= '0' && right.charAt(b) <= '9') b++;
                int significantA = startA, significantB = startB;
                while (significantA < a - 1 && left.charAt(significantA) == '0') significantA++;
                while (significantB < b - 1 && right.charAt(significantB) == '0') significantB++;
                int order = Integer.compare(a - significantA, b - significantB);
                if (order != 0) return order;
                order = left.substring(significantA, a).compareTo(right.substring(significantB, b));
                if (order != 0) return order;
                order = Integer.compare(a - startA, b - startB);
                if (order != 0) return order;
            } else { if (x != y) return Character.compare(x, y); a++; b++; }
        }
        int order = Integer.compare(left.length() - a, right.length() - b);
        return order == 0 ? left.compareTo(right) : order;
    }

    public static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[32 * 1024]; long total = 0; int count;
        while ((count = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Operación cancelada");
            total += count;
            if (total > limit) throw new IOException("Archivo demasiado grande (límite " + limit + " bytes)");
            out.write(buffer, 0, count);
        }
        return total;
    }

    public static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); copy(in, out, limit); return out.toByteArray();
    }

    public static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0)
            throw new IllegalArgumentException("Dimensiones de imagen inválidas");
        int sample = 1;
        while (((width + (long) sample - 1) / sample) * ((height + (long) sample - 1) / sample) > 3_000_000L
                || (width / sample / 2 >= targetWidth && height / sample / 2 >= targetHeight)) sample *= 2;
        return sample;
    }

    static InputStream limited(InputStream input, long limit) {
        return new FilterInputStream(input) {
            long read;
            @Override public int read() throws IOException {
                int value = in.read(); if (value >= 0 && ++read > limit) throw new IOException("Página demasiado grande"); return value;
            }
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                int actual = in.read(bytes, offset, (int) Math.min(count, limit - read + 1));
                if (actual > 0 && (read += actual) > limit) throw new IOException("Página demasiado grande"); return actual;
            }
            @Override public long skip(long count) throws IOException {
                long skipped = 0; byte[] buffer = new byte[8192];
                while (skipped < count) {
                    int actual = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                    if (actual < 0) break; skipped += actual;
                }
                return skipped;
            }
        };
    }
}
