package com.ecommerce.gateway.route;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Lab08: API Gateway - Core Logic
 *
 * 1. ROUTING: /api/products/** -> Product Service (VPC private IP)
 *             /api/orders/**   -> Product Service (VPC private IP)
 *             /api/files/**    -> File Manager Service
 *             /api/auth/**     -> SOAP Auth Service
 *
 * 2. CACHING: GET requests are cached in Redis
 *    - Cache HIT:  return from Redis (fast, ~1ms)
 *    - Cache MISS: call backend, store in Redis, return
 *
 * 3. CACHE INVALIDATION (Lab08 Bonus):
 *    POST/PUT/DELETE requests clear related cache keys
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class GatewayController {

    private final StringRedisTemplate redis;
    private final RestTemplate restTemplate;

    @Value("${service.product-order.url}")
    private String productOrderUrl;

    @Value("${service.soap-auth.url}")
    private String soapAuthUrl;

    @Value("${service.file-manager.url}")
    private String fileManagerUrl;

    @Value("${cache.ttl.seconds:60}")
    private int cacheTtl;

    // ======= PRODUCT ROUTES =======
    @RequestMapping("/products/**")
    public ResponseEntity<String> proxyProducts(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        return proxy(request, body, productOrderUrl, "/api/products", "/products");
    }

    // ======= ORDER ROUTES =======
    @RequestMapping("/orders/**")
    public ResponseEntity<String> proxyOrders(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        return proxy(request, body, productOrderUrl, "/api/orders", "/orders");
    }

    // ======= FILE ROUTES =======
    @RequestMapping("/files/**")
    public ResponseEntity<String> proxyFiles(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        // File uploads bypass cache
        return proxyNoCache(request, body, fileManagerUrl, "/api/files", "/files");
    }

    // ======= SOAP AUTH ROUTES (passthrough, no cache) =======
    @RequestMapping("/auth/**")
    public ResponseEntity<String> proxyAuth(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        return proxyNoCache(request, body, soapAuthUrl, "/api/auth", "/ws");
    }

    // ======= CACHE ADMIN =======
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> clearCache(
            @RequestParam(required = false) String pattern,
            HttpServletRequest request) {

        // Only internal or admin calls can clear cache
        String userRole = request.getHeader("X-User-Role");
        if (!"ADMIN".equals(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN can clear cache"));
        }

        String keyPattern = pattern != null ? "cache:" + pattern + "*" : "cache:*";
        Set<String> keys = redis.keys(keyPattern);
        long count = keys != null ? keys.size() : 0;
        if (keys != null) redis.delete(keys);

        log.info("Cache cleared: {} keys matching '{}'", count, keyPattern);
        return ResponseEntity.ok(Map.of("cleared", count, "pattern", keyPattern));
    }

    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> cacheStats() {
        Set<String> keys = redis.keys("cache:*");
        return ResponseEntity.ok(Map.of(
                "cachedKeys", keys != null ? keys.size() : 0,
                "ttlSeconds", cacheTtl
        ));
    }

    // ======= CORE PROXY LOGIC =======

    private ResponseEntity<String> proxy(
            HttpServletRequest request, String body,
            String backendBaseUrl, String gatewayPrefix, String backendPrefix) {

        String method = request.getMethod();
        String path = request.getRequestURI().replace(gatewayPrefix, backendPrefix);
        String queryString = request.getQueryString();
        String fullUrl = backendBaseUrl + path + (queryString != null ? "?" + queryString : "");
        String cacheKey = "cache:" + path + (queryString != null ? "?" + queryString : "");

        // Lab08: Cache logic for GET requests
        if ("GET".equals(method)) {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("[CACHE HIT]  {} {}", method, path);
                return ResponseEntity.ok()
                        .header("X-Cache", "HIT")
                        .header("Content-Type", "application/json")
                        .body(cached);
            }
            log.debug("[CACHE MISS] {} {}", method, path);
        }

        // Forward request to backend service
        ResponseEntity<String> backendResponse = forwardRequest(
                request, body, fullUrl, method);

        // Cache successful GET responses
        if ("GET".equals(method) && backendResponse.getStatusCode().is2xxSuccessful()) {
            redis.opsForValue().set(cacheKey, backendResponse.getBody(),
                    Duration.ofSeconds(cacheTtl));
            log.debug("[CACHE SET]  {} -> TTL {}s", cacheKey, cacheTtl);
        }

        // Lab08 Bonus: Invalidate cache on mutations
        if (List.of("POST", "PUT", "DELETE", "PATCH").contains(method)) {
            invalidateRelatedCache(backendPrefix, path);
        }

        return ResponseEntity.status(backendResponse.getStatusCode())
                .headers(backendResponse.getHeaders())
                .header("X-Cache", "MISS")
                .body(backendResponse.getBody());
    }

    private ResponseEntity<String> proxyNoCache(
            HttpServletRequest request, String body,
            String backendBaseUrl, String gatewayPrefix, String backendPrefix) {

        String method = request.getMethod();
        String path = request.getRequestURI().replace(gatewayPrefix, backendPrefix);
        String queryString = request.getQueryString();
        String fullUrl = backendBaseUrl + path + (queryString != null ? "?" + queryString : "");

        log.debug("[NO CACHE]   {} {}", method, path);
        return forwardRequest(request, body, fullUrl, method);
    }

    private ResponseEntity<String> forwardRequest(
            HttpServletRequest request, String body, String url, String method) {
        try {
            HttpHeaders headers = copyHeaders(request);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            HttpMethod httpMethod = HttpMethod.valueOf(method);

            return restTemplate.exchange(url, httpMethod, entity, String.class);
        } catch (Exception e) {
            log.error("Backend call failed [{}]: {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Backend service unavailable\"}");
        }
    }

    /**
     * Lab08 Bonus: Cache Invalidation
     * When a product is updated/deleted, clear product cache
     */
    private void invalidateRelatedCache(String prefix, String path) {
        String pattern = "cache:" + prefix + "*";
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.debug("[CACHE INVALIDATE] Cleared {} keys for pattern: {}", keys.size(), pattern);
        }
    }

    private HttpHeaders copyHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Forward auth and user context headers
        List<String> forwardHeaders = List.of(
                "Authorization", "X-User-Id", "X-Username", "X-User-Role");
        for (String header : forwardHeaders) {
            String value = request.getHeader(header);
            if (value != null) headers.set(header, value);
        }
        return headers;
    }
}
