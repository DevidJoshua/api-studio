package io.apistudio.engine.runtime;

import io.apistudio.contracts.exec.ExecutionRequest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Registry mock dependensi HTTP (BRD FR-SIM-04).
 *
 * <p>Pencocokan memakai pola glob sederhana ({@code *} dan {@code **}) terhadap
 * URL. Mock pertama yang cocok dipakai, sehingga urutan konfigurasi menentukan
 * prioritas.
 */
public final class MockRegistry {

    private final List<ExecutionRequest.HttpMock> mocks;

    public MockRegistry(List<ExecutionRequest.HttpMock> mocks) {
        this.mocks = mocks == null ? List.of() : List.copyOf(mocks);
    }

    public static MockRegistry empty() {
        return new MockRegistry(List.of());
    }

    public boolean isEmpty() {
        return mocks.isEmpty();
    }

    /**
     * Cari mock untuk URL tertentu.
     *
     * @return mock yang cocok, atau {@code null} bila tidak ada (pemanggilan nyata)
     */
    public ExecutionRequest.HttpMock find(String url) {
        if (url == null) {
            return null;
        }
        for (ExecutionRequest.HttpMock mock : mocks) {
            if (mock == null || mock.urlPattern() == null) {
                continue;
            }
            if (url.equals(mock.urlPattern()) || globToRegex(mock.urlPattern()).matcher(url).matches()) {
                return mock;
            }
        }
        return null;
    }

    /** Terjemahkan glob ({@code *}, {@code **}) menjadi regex. */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else {
                if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
                i++;
            }
        }
        return Pattern.compile(regex.toString());
    }
}
