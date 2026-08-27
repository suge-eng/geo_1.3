package com.geo.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
    public ResponseEntity<byte[]> proxyTask(HttpServletRequest request) {
        return doProxy(request, TASK_SERVICE);
    }

    @RequestMapping(value = "/api/analysis/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyAnalysis(HttpServletRequest request) {
        return doProxy(request, ANALYSIS_SERVICE);
    }

    @RequestMapping(value = "/api/file/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyFile(HttpServletRequest request) {
        return doProxy(request, FILE_SERVICE);
    }

    @RequestMapping(value = "/api/rpa/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyRpa(HttpServletRequest request) {
        return doProxy(request, RPA_SERVICE);
    }

    private ResponseEntity<byte[]> doProxy(HttpServletRequest request, String targetBase) {
        try {
            String requestUri = request.getRequestURI();
            String queryString = request.getQueryString();
            String targetUrl = targetBase + requestUri;
            if (queryString != null && !queryString.isEmpty()) {
                targetUrl = targetUrl + "?" + queryString;
            }

            HttpHeaders headers = extractHeaders(request);
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            String contentType = request.getContentType();

            RequestEntity<?> requestEntity;

            boolean isMultipart = contentType != null && contentType.toLowerCase().contains(MediaType.MULTIPART_FORM_DATA_VALUE);

            if (isMultipart && (request instanceof MultipartHttpServletRequest)) {
                MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
                MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();

                Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
                for (Map.Entry<String, MultipartFile> entry : fileMap.entrySet()) {
                    MultipartFile mf = entry.getValue();
                    final String filename = mf.getOriginalFilename() == null ? entry.getKey() : mf.getOriginalFilename();
                    final byte[] bytes = mf.getBytes();
                    final String partContentType = mf.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mf.getContentType();
                    parts.add(entry.getKey(), new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return filename;
                        }
                    });
                    HttpHeaders partHeaders = new HttpHeaders();
                    partHeaders.setContentType(MediaType.parseMediaType(partContentType));
                }

                Map<String, String[]> paramMap = multipartRequest.getParameterMap();
                for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
                    String[] values = entry.getValue();
                    if (values == null) continue;
                    for (String v : values) {
                        parts.add(entry.getKey(), v);
                    }
                }

                HttpHeaders multipartHeaders = new HttpHeaders();
                multipartHeaders.putAll(headers);
                multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

                requestEntity = new RequestEntity<>(parts, multipartHeaders, method, new URI(targetUrl));
            } else {
                byte[] body = readRequestBody(request);
                if (body != null && body.length > 0) {
                    requestEntity = new RequestEntity<>(body, headers, method, new URI(targetUrl));
                } else {
                    requestEntity = new RequestEntity<>(headers, method, new URI(targetUrl));
                }
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
        } catch (URISyntaxException | IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("代理请求失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] readRequestBody(HttpServletRequest request) throws IOException {
        try (InputStream is = request.getInputStream()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            StreamUtils.copy(is, baos);
            return baos.toByteArray();
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