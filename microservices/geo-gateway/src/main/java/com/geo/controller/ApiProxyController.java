package com.geo.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

@RestController
public class ApiProxyController {

    private final RestTemplate restTemplate;

    private static final String TASK_SERVICE = "http://localhost:8081";
    private static final String ANALYSIS_SERVICE = "http://localhost:8082";
    private static final String FILE_SERVICE = "http://localhost:8083";
    private static final String RPA_SERVICE = "http://localhost:8084";

    public ApiProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @RequestMapping(value = "/api/task/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyTask(HttpServletRequest request,
                                            @RequestBody(required = false) byte[] body) {
        return doProxy(request, body, TASK_SERVICE);
    }

    @RequestMapping(value = "/api/analysis/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyAnalysis(HttpServletRequest request,
                                                @RequestBody(required = false) byte[] body) {
        return doProxy(request, body, ANALYSIS_SERVICE);
    }

    @RequestMapping(value = "/api/file/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyFile(HttpServletRequest request,
                                            @RequestBody(required = false) byte[] body) {
        return doProxy(request, body, FILE_SERVICE);
    }

    @RequestMapping(value = "/api/rpa/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyRpa(HttpServletRequest request,
                                           @RequestBody(required = false) byte[] body) {
        return doProxy(request, body, RPA_SERVICE);
    }

    private ResponseEntity<byte[]> doProxy(HttpServletRequest request, byte[] body, String targetBase) {
        try {
            String requestUri = request.getRequestURI();
            String queryString = request.getQueryString();
            String targetUrl = targetBase + requestUri;
            if (queryString != null && !queryString.isEmpty()) {
                targetUrl = targetUrl + "?" + queryString;
            }

            HttpHeaders headers = extractHeaders(request);

            HttpMethod method = HttpMethod.valueOf(request.getMethod());

            RequestEntity<byte[]> requestEntity;
            if (body != null && body.length > 0) {
                requestEntity = new RequestEntity<>(body, headers, method, new URI(targetUrl));
            } else {
                requestEntity = new RequestEntity<>(headers, method, new URI(targetUrl));
            }

            ResponseEntity<byte[]> response = restTemplate.exchange(requestEntity, byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
                String headerName = entry.getKey();
                if (!headerName.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING)
                        && !headerName.equalsIgnoreCase(HttpHeaders.CONNECTION)
                        && !headerName.equalsIgnoreCase("keep-alive")
                        && !headerName.equalsIgnoreCase("server")) {
                    responseHeaders.put(headerName, entry.getValue());
                }
            }

            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
        } catch (URISyntaxException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("代理请求失败: " + e.getMessage()).getBytes());
        }
    }

    private HttpHeaders extractHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                    || headerName.equalsIgnoreCase(HttpHeaders.HOST)
                    || headerName.equalsIgnoreCase(HttpHeaders.CONNECTION)
                    || headerName.equalsIgnoreCase("keep-alive")
                    || headerName.equalsIgnoreCase("accept-encoding")) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                headers.add(headerName, values.nextElement());
            }
        }
        return headers;
    }
}
