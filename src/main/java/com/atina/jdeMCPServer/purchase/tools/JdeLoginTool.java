package com.atina.jdeMCPServer.purchase.tools;

import com.atina.jdeMCPServer.auth.JdeAuthClient;
import com.atina.jdeMCPServer.auth.JdeAuthService;
import com.atina.jdeMCPServer.purchase.services.JdePurchaseOrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class JdeLoginTool {

    private static final Logger log = LoggerFactory.getLogger(JdeLoginTool.class);

    private final JdeAuthClient authClient;
    private final JdeAuthService authService;

    public JdeLoginTool(JdeAuthClient authClient, JdeAuthService authService) {
        this.authClient  = authClient;
        this.authService = authService;
    }

    @McpTool(
            name = "jde_login",
            description = """
                Authenticates the user in JDE through the Mulesoft API.
                MUST be called before any other JDE operation.

                IMPORTANT FOR THE ASSISTANT:
                - Always call this tool first if no active JDE session exists.
                - If any other tool returns a session error, call this tool again
                  and then retry the original operation.
                - Ask the user for their JDE username and password if not already provided.
                - Never store or repeat the password back to the user.
                - After a successful login, confirm the authenticated user and session expiry.
                """
    )
    public String jdeLogin(
            @McpToolParam(description = "JDE username, e.g.: JDOE")
            String user,
            @McpToolParam(description = "JDE password")
            String password
    ) {
        try {
            log.info("Login attempt for JDE user: {}", user.toUpperCase());

            JdeAuthClient.LoginResult result = authClient.login(user, password);

            authService.storeToken(result.token());

            log.info("Login successful for JDE user: {}", user.toUpperCase());

            String expiry = result.expiresAt() != null
                    ? "Session expires at: " + result.expiresAt() + "."
                    : "Session is now active.";

            return String.format(
                    "Successfully authenticated as %s. %s",
                    user.toUpperCase(),
                    expiry
            );

        } catch (Exception e) {
            log.error("Login failed for JDE user: {}", user.toUpperCase(), e);
            return String.format(
                    "Authentication failed for user %s: %s. " +
                            "Please verify your JDE username and password.",
                    user.toUpperCase(),
                    e.getMessage()
            );
        }
    }
}