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

/**
 * API 统一代理入口（网关核心）。
 *
 * 设计思路：本项目没有引入 Spring Cloud Gateway 那一整套网关框架，而是用最朴素的
 * SpringMVC 控制器手写转发逻辑，原因是——
 *   1. 后端各微服务本地直连、地址固定，路由规则简单，不值得引入重量级网关；
 *   2. 自定义转发能在“转发前 / 转发后”插入任意的业务处理（例如跨服务鉴权、
 *      请求头裁剪、multipart 文件透传），控制力更强、更容易给新人讲清楚。
 *
 * 路由方式：前端请求统一以“/api/xxx/**”的形式打到这里，控制器根据 “xxx”
 * 这段路径前缀把请求分发到对应的微服务。各服务地址见下方常量，举例：
 *   /api/task/**     -> 任务服务  (8081)
 *   /api/analysis/** -> 分析服务  (8082)
 *   /api/file/**     -> 文件服务  (8083)
 *   /api/rpa/**      -> RPA 服务  (8084)
 *
 * 鉴权说明：前端发来的请求会带上用户令牌（如 Authorization 头），doProxy 在
 * extractHeaders 里把它原样透传给后端服务，由各服务自行校验，网关只做“搬运”。
 */
@RestController
public class ApiProxyController {

    // RestTemplate 由 RestTemplateConfig 注入，负责真正发出对下游服务的 HTTP 请求
    private final RestTemplate restTemplate;

    // 各下游微服务的地址常量。目标 URL = service 地址 + 原始请求路径。
    private static final String TASK_SERVICE = "http://localhost:8081";
    private static final String ANALYSIS_SERVICE = "http://localhost:8082";
    private static final String FILE_SERVICE = "http://localhost:8083";
    private static final String RPA_SERVICE = "http://localhost:8084";

    public ApiProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ===== 下面是四个路由入口，每个入口只负责“认领”一类前缀，并把请求交给统一的 doProxy 处理 =====

    // /api/task/**  -> 任务服务
    @RequestMapping(value = "/api/task/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyTask(HttpServletRequest request) {
        return doProxy(request, TASK_SERVICE);
    }

    // /api/analysis/** -> 分析服务
    @RequestMapping(value = "/api/analysis/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyAnalysis(HttpServletRequest request) {
        return doProxy(request, ANALYSIS_SERVICE);
    }

    // /api/file/** -> 文件服务
    @RequestMapping(value = "/api/file/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyFile(HttpServletRequest request) {
        return doProxy(request, FILE_SERVICE);
    }

    // /api/rpa/** -> RPA 服务
    @RequestMapping(value = "/api/rpa/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyRpa(HttpServletRequest request) {
        return doProxy(request, RPA_SERVICE);
    }

    /**
     * 真正执行转发的方法。
     *
     * 目标地址拼接规则：targetBase（如 http://localhost:8081）+ 原始请求路径
     * （含 /api/task/xxx），再拼上查询字符串。所以下游服务看到的路径和网关一致，
     * 便于下游用同样的 @RequestMapping 接收。
     *
     * 返回类型固定为 ResponseEntity&lt;byte[]&gt;：用字节数组承载响应，是为了
     * 能原样透传任意格式的内容（JSON、文件流、图片等），不被字符串编码破坏。
     */
    private ResponseEntity<byte[]> doProxy(HttpServletRequest request, String targetBase) {
        try {
            // 拼目标地址：服务地址 + 原始路径；如有查询串（?a=1&b=2）也要原样带上
            String requestUri = request.getRequestURI();
            String queryString = request.getQueryString();
            String targetUrl = targetBase + requestUri;
            if (queryString != null && !queryString.isEmpty()) {
                targetUrl = targetUrl + "?" + queryString;
            }

            // 提取请求头（会过滤掉 Host、Content-Length 等由底层重新生成的头部），
            // 并把方法、内容类型一并取出来，供构造下游请求使用。
            HttpHeaders headers = extractHeaders(request);
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            String contentType = request.getContentType();

            RequestEntity<?> requestEntity;

            // 是否为 multipart 表单（即文件上传）。这类请求体不能当作普通字节流读取，
            // 需要特殊分支把“文件 + 普通字段”一起打包透传。
            boolean isMultipart = contentType != null && contentType.toLowerCase().contains(MediaType.MULTIPART_FORM_DATA_VALUE);

            if (isMultipart && (request instanceof MultipartHttpServletRequest)) {
                MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
                // parts 按“一个 key 可对应多个值”的结构收集所有上传内容（文件或普通文本）
                MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();

                // 先把文件逐个读进来，包装成 ByteArrayResource（Spring 用来表示
                // 内存中字节流的资源类型），这样 RestTemplate 能把它当文件片段发送。
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

                // 再把普通表单字段（非文件）也追加到 parts 中，一个 key 可能有多个值
                Map<String, String[]> paramMap = multipartRequest.getParameterMap();
                for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
                    String[] values = entry.getValue();
                    if (values == null) continue;
                    for (String v : values) {
                        parts.add(entry.getKey(), v);
                    }
                }

                // 复用原请求头，但要强制把 Content-Type 设为 multipart/form-data，
                // 否则下游无法正确解析；真正的分界与各片段类型交给 RestTemplate 生成。
                HttpHeaders multipartHeaders = new HttpHeaders();
                multipartHeaders.putAll(headers);
                multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

                requestEntity = new RequestEntity<>(parts, multipartHeaders, method, new URI(targetUrl));
            } else {
                // 非文件上传：直接把请求体读成字节数组转发（GET 等无请求体则为空）。
                byte[] body = readRequestBody(request);
                if (body != null && body.length > 0) {
                    requestEntity = new RequestEntity<>(body, headers, method, new URI(targetUrl));
                } else {
                    requestEntity = new RequestEntity<>(headers, method, new URI(targetUrl));
                }
            }

            // 真正发出请求，并把响应整个拿回来（字节数组形式）。
            ResponseEntity<byte[]> response = restTemplate.exchange(requestEntity, byte[].class);

            // 拷贝响应头，但要剔除与“单次连接”相关的头（Transfer-Encoding、
            // Connection、keep-alive、Server），它们由网关自己的容器重新生成。
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
            // 转发过程中出现异常时，统一返回 500 并带上可读的错误信息。
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("代理请求失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    // 读取原始请求体为字节数组（try-with-resources 会自动关闭输入流）
    private byte[] readRequestBody(HttpServletRequest request) throws IOException {
        try (InputStream is = request.getInputStream()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            StreamUtils.copy(is, baos);
            return baos.toByteArray();
        }
    }

    // 提取并整理请求头：跳过那些由底层连接管理、不该手动透传的头，其余原样转发。
    private HttpHeaders extractHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            // 这些头不能原样透传：
            //   Content-Length   —— 实际长度由 RestTemplate 根据新请求体重算；
            //   Host             —— 目标主机已变，必须指向下游服务；
            //   Connection / keep-alive —— 连接管理头，由底层重新生成；
            //   accept-encoding  —— 避免下游对响应做 gzip 压缩，导致网关拿到压缩字节难处理。
            // 其余头（含 Authorization 令牌）全部透传，实现“跨服务鉴权”。
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