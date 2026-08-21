package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.exception.ErrorCode;
import org.example.common.tool.ToolErrors;
import org.example.common.tool.ToolExecutionTemplate;
import org.example.common.tool.ToolResult;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;
    private final ToolExecutionTemplate toolExecutionTemplate;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService, ToolExecutionTemplate toolExecutionTemplate) {
        this.vectorSearchService = vectorSearchService;
        this.toolExecutionTemplate = toolExecutionTemplate;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        

        ToolResult<List<VectorSearchService.SearchResult>> result = toolExecutionTemplate.execute(TOOL_QUERY_INTERNAL_DOCS,
                "query=" + query + ", topK=" + topK,
                () -> vectorSearchService.searchSimilarDocuments(query, topK),
                exception -> ToolErrors.from(ErrorCode.MILVUS_UNAVAILABLE, exception));
        if (result.isSuccess()) {
            result.setMessage(result.getData().isEmpty()
                    ? "知识库中未找到相关文档"
                    : String.format("成功检索到 %d 条相关文档", result.getData().size()));
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception serializationException) {
            logger.error("[工具错误] queryInternalDocs 结果序列化失败", serializationException);
            return "{\"success\":false,\"attempts\":1,\"message\":\"知识库查询失败\"}";
        }
    }
}
