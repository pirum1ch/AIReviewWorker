package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds a second Tomcat connector on the info port (default 8082) and registers a
 * {@link PortIsolationInterceptor} that restricts {@code /worker/**} to that port and blocks it on
 * the actuator port (8081).
 *
 * <p>Both ports share the same {@link org.springframework.web.servlet.DispatcherServlet} and
 * application context — a fully isolated second context is unnecessary given that the interceptor
 * enforces the routing boundary, and it would add significant startup complexity with no security gain
 * (both ports are already bound to 127.0.0.1 by default).
 */
@Configuration
public class InfoServerConfig implements WebMvcConfigurer {

    private final WorkerProperties props;
    private final int serverPort;

    public InfoServerConfig(WorkerProperties props,
                             @Value("${server.port:8081}") int serverPort) {
        this.props = props;
        this.serverPort = serverPort;
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> addInfoConnector() {
        return factory -> {
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(props.getInfo().getPort());
            connector.setProperty("address", props.getInfo().getBindAddress());
            factory.addAdditionalTomcatConnectors(connector);
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PortIsolationInterceptor(serverPort, props.getInfo().getPort()));
    }
}
