package com.example.dbadmin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig implements WebMvcConfigurer {
    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ThreadPoolTaskExecutor mvcStreamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("dbadmin-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcStreamingExecutor());
        configurer.setDefaultTimeout(7_200_000);
    }

    /**
     * 认证开着的时候，不接受能匹配任意来源的 CORS pattern。
     *
     * <p>跨域请求现在会带会话 Cookie，而 {@code /api/auth/status} 会返回 CSRF 令牌 ——
     * 通配来源加上 allowCredentials 等于把整套写接口交给任意网页。见 {@link CorsOriginPolicy}。</p>
     */
    @PostConstruct
    void validateCorsOrigins() {
        if ("DISABLED".equalsIgnoreCase(properties.getAuth().getMode() == null ? "DISABLED" : properties.getAuth().getMode().trim())) return;
        List<String> unsafe = CorsOriginPolicy.patternsMatchingAnyOrigin(properties.getCors().getAllowedOriginPatterns());
        if (unsafe.isEmpty()) return;
        throw new IllegalStateException(
                "启用 Web 认证时 app.cors.allowed-origin-patterns 不能匹配任意来源："
                        + String.join("、", unsafe)
                        + "。跨域请求会带上会话 Cookie，通配来源等于把 CSRF 令牌和全部写接口交给任意网页；"
                        + "请改成具体的 UI 来源，例如 https://db.example.com。"
        );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(properties.getCors().getAllowedOriginPatterns().toArray(String[]::new))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite fingerprints every production asset filename. These files can
        // therefore be cached permanently while index.html remains revalidated.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
