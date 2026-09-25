package de.farmpulse.rpsim.config;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Local-only tool (no auth in V1): allow the Angular dev server on localhost and - in a release - serve the built
 * frontend from {@code rpsim.web.static-dir} with a fallback to index.html for client-side routes.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RpsimProperties props;

    public WebConfig(RpsimProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (servesFrontend()) {
            registry.addViewController("/").setViewName("forward:/index.html"); // empty paths never reach the handler
        }
    }

    private boolean servesFrontend() {
        String dir = props.getWeb().getStaticDir();
        return dir != null && !dir.isBlank();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!servesFrontend()) {
            return;
        }
        Path root = Path.of(props.getWeb().getStaticDir()).toAbsolutePath().normalize();
        registry.addResourceHandler("/**")
                .addResourceLocations(new FileSystemResource(root.toString() + "/"))
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // client-side route (e.g. /mailbox?id=3) -> the Angular app handles it
                        return resourcePath.contains(".") ? null : new FileSystemResource(root.resolve("index.html"));
                    }
                });
    }
}
