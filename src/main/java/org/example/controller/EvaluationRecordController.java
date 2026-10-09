package org.example.controller;

import org.example.common.api.ApiResponse;
import org.example.common.exception.BusinessException;
import org.example.common.exception.ErrorCode;
import org.example.service.EvaluationRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测评历史查询接口。
 *
 * @author Codex
 * @date 2026-09-28
 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationRecordController {

    private final EvaluationRecordService evaluationRecordService;

    public EvaluationRecordController(EvaluationRecordService evaluationRecordService) {
        this.evaluationRecordService = evaluationRecordService;
    }

    /**
     * 分页查询测评历史。
     *
     * @param type 产物类型
     * @param dataset 数据集
     * @param caseId 案例标识
     * @param status 运行状态
     * @param page 页码，从零开始
     * @param size 每页数量
     * @return 测评记录分页
     */
    @GetMapping
    public ResponseEntity<ApiResponse<EvaluationRecordService.EvaluationRecordPage>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String dataset,
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        EvaluationRecordService.EvaluationRecordPage result = evaluationRecordService.list(
                type, dataset, caseId, status, page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 查询单条测评记录及其请求和响应快照。
     *
     * @param id 数据库自增 ID
     * @return 测评记录详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EvaluationRecordService.EvaluationRecordDetail>> get(
            @PathVariable long id) {
        EvaluationRecordService.EvaluationRecordDetail result = evaluationRecordService.findById(id);
        if (result == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评记录不存在");
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
