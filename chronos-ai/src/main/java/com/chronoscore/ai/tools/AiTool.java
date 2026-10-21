package com.chronoscore.ai.tools;

import java.util.Map;

/**
 * Controlled declaration of an operational tool available to the AI layer.
 * All tools are statically declared; models can never dynamically invent or execute arbitrary tools.
 */
public record AiTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        ToolPermission permission,
        boolean readOnly,
        boolean tenantScoped
) {}
