package com.cmc.cloud.cmclink.mcp.config;

import com.cmc.cloud.cmclink.mcp.framework.OpenApiToolCallbackProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * 向量工具索引：将工具名称和描述嵌入到 Qdrant 向量存储中，支持语义检索
     */
    @Bean
    public ToolIndex toolIndex(VectorStore vectorStore) {
        return new VectorToolIndex(vectorStore);
    }

    /**
     * 工具检索 Advisor：替代默认的 ToolCallingAdvisor，
     * 不再将所有 tool 定义一次性发送给 LLM，而是按需通过向量检索匹配的工具
     */
    @Bean
    public ToolSearchToolCallingAdvisor toolSearchAdvisor(ToolIndex toolIndex) {
        return ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .maxResults(10)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 OpenApiToolCallbackProvider openApiToolCallbackProvider,
                                 ToolSearchToolCallingAdvisor toolSearchAdvisor) {
        return builder
                .defaultTools(openApiToolCallbackProvider)
                .defaultAdvisors(toolSearchAdvisor)
                .build();
    }
}
