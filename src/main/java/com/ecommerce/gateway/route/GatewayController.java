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

    @RequestMapping("/products/**")
    public ResponseEntity<String> proxyProducts(HttpServletRequest request,
            @RequestBody(required = false) String body) {
        return proxy(request, body, productOrderUrl, "/api/products", "/products");
    }

    @RequestMapping("/orders/**")
    public ResponseEntity<String> proxyOrders(HttpServletRequest request,
            @RequestBody(required = false) String body) {
        return proxy(request, body, productOrderUrl, "/api/orders", "/orders");
    }

    @RequestMapping("/files/**")
    public ResponseEntity<?> proxyFiles(HttpServletRequest request,
            @RequestBody(required = false) String body) {
        
        String contentType = request.getContentType();
        
        // Multipart (file upload) — тусад нь дамжуулна
        if (contentType != null && contentType.startsWith("multipart/")) {
            try {
                String path = request.getRequestURI().replace("/api/files", "/files");
                String fullUrl = fileManagerUrl + path;
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.valueOf(contentType));
                if (getToken(request) != null) headers.set("Authorization", getToken(request));
                
                byte[] bodyBytes = request.getInputStream().readAllBytes();
                HttpEntity<byte[]> entity = new HttpEntity<>(bodyBytes, headers);
                return restTemplate.exchange(fullUrl, HttpMethod.POST, entity, String.class);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body("{\"error\":\"File upload failed\"}");
            }
        }
        
        return proxyNoCache(request, body, fileManagerUrl, "/api/files", "/files");
    }

    private String getToken(HttpServletRequest request) {
        return request.getHeader("Authorization");
    }

    @RequestMapping("/auth/**")
    public ResponseEntity<String> proxyAuth(HttpServletRequest request,
            @RequestBody(required = false) String body) {
        return proxyNoCache(request, body, soapAuthUrl, "/api/auth", "/ws/auth");
        //                                                              ^^^^^^^^^^
    }
    private ResponseEntity<String> proxy(HttpServletRequest request, String body,
            String backendBaseUrl, String gatewayPrefix, String backendPrefix) {

        String method = request.getMethod();
        String path = request.getRequestURI().replace(gatewayPrefix, backendPrefix);
        String queryString = request.getQueryString();
        String fullUrl = backendBaseUrl + path + (queryString != null ? "?" + queryString : "");
        String cacheKey = "cache:" + path + (queryString != null ? "?" + queryString : "");

        if ("GET".equals(method)) {
            try {
                String cached = redis.opsForValue().get(cacheKey);
                if (cached != null) {
                    log.debug("[CACHE HIT]  {} {}", method, path);
                    return ResponseEntity.ok()
                            .header("X-Cache", "HIT")
                            .header("Content-Type", "application/json")
                            .body(cached);
                }
            } catch (Exception e) {
                log.warn("Redis read failed: {}", e.getMessage());
            }
            log.debug("[CACHE MISS] {} {}", method, path);
        }

        ResponseEntity<String> backendResponse = forwardRequest(request, body, fullUrl, method);

        if ("GET".equals(method) && backendResponse.getStatusCode().is2xxSuccessful()) {
            try {
                redis.opsForValue().set(cacheKey, backendResponse.getBody(), Duration.ofSeconds(cacheTtl));
            } catch (Exception e) {
                log.warn("Redis write failed: {}", e.getMessage());
            }
        }

        if (List.of("POST", "PUT", "DELETE", "PATCH").contains(method)) {
            try {
                Set<String> keys = redis.keys("cache:" + backendPrefix + "*");
                if (keys != null && !keys.isEmpty()) redis.delete(keys);
            } catch (Exception e) {
                log.warn("Redis invalidate failed: {}", e.getMessage());
            }
        }

        return ResponseEntity.status(backendResponse.getStatusCode())
                .headers(backendResponse.getHeaders())
                .header("X-Cache", "MISS")
                .body(backendResponse.getBody());
    }

    private ResponseEntity<String> proxyNoCache(HttpServletRequest request, String body,
            String backendBaseUrl, String gatewayPrefix, String backendPrefix) {
        String method = request.getMethod();
        String path = request.getRequestURI().replace(gatewayPrefix, backendPrefix);
        String queryString = request.getQueryString();
        String fullUrl = backendBaseUrl + path + (queryString != null ? "?" + queryString : "");
        return forwardRequest(request, body, fullUrl, method);
    }

    private ResponseEntity<String> forwardRequest(HttpServletRequest request, String body,
            String url, String method) {
        try {
            HttpHeaders headers = copyHeaders(request);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            return restTemplate.exchange(url, HttpMethod.valueOf(method), entity, String.class);
        } catch (Exception e) {
            log.error("Backend call failed [{}]: {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Backend service unavailable\"}");
        }
    }

    private HttpHeaders copyHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        
        // Content-Type-ийг request-аас хадгална (SOAP = text/xml, REST = application/json)
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains("text/xml")) {
            headers.setContentType(MediaType.TEXT_XML);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        
        List<String> forwardHeaders = List.of("Authorization", "X-User-Id", "X-Username", "X-User-Role");
        for (String header : forwardHeaders) {
            String value = request.getHeader(header);
            if (value != null) headers.set(header, value);
        }
        return headers;
    }
}