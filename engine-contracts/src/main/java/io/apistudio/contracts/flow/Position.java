package io.apistudio.contracts.flow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Posisi node pada kanvas Studio.
 *
 * <p>Hanya dipakai untuk tata letak; Flow Engine mengabaikannya saat eksekusi.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(double x, double y) {

    public static Position of(double x, double y) {
        return new Position(x, y);
    }

    public static Position origin() {
        return new Position(0, 0);
    }
}
