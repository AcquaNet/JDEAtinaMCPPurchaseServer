package com.atina.jdeMCPServer;

import com.atina.jdeMCPServer.purchase.services.JdePurchaseOrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {

    private static final Logger log =
            LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {

        ConfigurableApplicationContext context =
                SpringApplication.run(Application.class, args);

        Environment environment = context.getEnvironment();

        String port = environment.getProperty("server.port", "8080");
        String applicationName =
                environment.getProperty("spring.application.name", "Unknown");
        String mcpServerName =
                environment.getProperty("mcp.ai.mcp.server.name", "Unknown");
        String mcpServerVersion =
                environment.getProperty("mcp.ai.mcp.server.version", "Unknown");

        log.info("============================================================");
        log.info("Application Name : {}", applicationName);
        log.info("Server Port      : {}", port);
        log.info("MCP Server Name  : {}", mcpServerName);
        log.info("MCP Version      : {}", mcpServerVersion);
        log.info("MCP Endpoint     : http://localhost:{}/mcp", port);
        log.info("============================================================");
    }
}
