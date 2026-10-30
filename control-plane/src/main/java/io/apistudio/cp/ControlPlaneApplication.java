package io.apistudio.cp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Titik masuk Control Plane (BRD Bagian 5.1).
 *
 * <p>Control Plane menyimpan definisi API dan flow, menangani versioning,
 * validasi, kompilasi, publish, RBAC, dan audit. Flow Engine disematkan pada
 * MVP agar deployment sederhana; pemisahan dijaga di tingkat kontrak.
 */
@SpringBootApplication
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
