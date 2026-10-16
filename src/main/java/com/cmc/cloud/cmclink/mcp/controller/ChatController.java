package com.cmc.cloud.cmclink.mcp.controller;

import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class ChatController {

    @Resource
    private ChatClient chatClient;

    /**
     * 调用 MCP 工具的示例接口
     */
    @GetMapping("/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "帮我发起一个简单连续多节点审批工作流, L1审批用户为胡鹏，L2审批用户为集运管理员，L3审批用户为胡鹏") String message) {
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt(message);
            request.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, IdUtil.fastUUID()));

            ChatClient.CallResponseSpec response = request.call();

            String content = response.content();
            return content;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
}
