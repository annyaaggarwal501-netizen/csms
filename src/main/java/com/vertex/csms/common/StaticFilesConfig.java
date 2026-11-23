package com.vertex.csms.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Faster page changes: the app's CSS and JavaScript files are kept by the browser (no round trip on every page),
 * and every page asks for them as "js/app.js?v=<version>". The version changes with every start of the app
 * (so after each deploy), which makes browsers fetch the new files straight away. Pages (HTML) are always fresh.
 */
@Configuration
public class StaticFilesConfig implements WebMvcConfigurer {

    /** Changes on every start (= every deploy). */
    static final String VERSION = Long.toString(System.currentTimeMillis(), 36);

    private static final Pattern ASSET = Pattern.compile("((?:src|href)=\")((?:js|css)/[^\"?#]+\\.(?:js|css))(\")");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/js/**", "/css/**")
                .addResourceLocations("classpath:/static/js/", "classpath:/static/css/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .setUseLastModified(false)            // pages are rebuilt with the current version every time
                .resourceChain(true)
                .addTransformer(new VersionedAssets());
    }

    /** Adds ?v=<version> to the CSS/JS links of every HTML page. */
    static class VersionedAssets implements ResourceTransformer {
        @Override
        public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
                throws IOException {
            Resource r = chain.transform(request, resource);
            String name = r.getFilename();
            if (name == null || !name.endsWith(".html")) return r;
            String html = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
            Matcher m = ASSET.matcher(html);
            String out = m.replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + mr.group(2) + "?v=" + VERSION + mr.group(3)));
            return new TransformedResource(r, out.getBytes(StandardCharsets.UTF_8));
        }
    }
}
