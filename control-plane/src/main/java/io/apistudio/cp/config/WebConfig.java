package io.apistudio.cp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Konfigurasi CORS untuk Studio (dev server Vite berjalan pada port berbeda).
 *
 * <p>Di produksi, Studio disajikan dari origin yang sama atau melalui reverse
 * proxy sehingga daftar origin dapat dipersempit. Origin diambil dari
 * konfigurasi agar tidak perlu mengubah kode saat deploy.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public WebConfig(@Value("${apistudio.cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Flow-Error", "X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
