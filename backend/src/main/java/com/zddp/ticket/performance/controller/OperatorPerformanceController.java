package com.zddp.ticket.performance.controller;

import com.zddp.ticket.common.Result;
import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.common.PageResult;
import com.zddp.ticket.performance.model.dto.SavePerformanceDTO;
import com.zddp.ticket.performance.model.vo.PerformanceVO;
import com.zddp.ticket.performance.service.PerformanceService;
import com.zddp.ticket.user.model.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营演出接口，要求 OPERATOR 角色（普通用户访问一律 403，由 SecurityConfig 统一拦截）。
 *
 * <p>「当前运营人员是谁」完全来自服务端会话中的认证上下文，
 * 请求体或查询参数里出现的 userId / publisherId 不会被采纳，
 * 而且这类字段属于未知字段，会被直接拒绝为 400。
 */
@RestController
@RequestMapping("/api/operator/performances")
public class OperatorPerformanceController {

    private final PerformanceService performanceService;

    public OperatorPerformanceController(PerformanceService performanceService) {
        this.performanceService = performanceService;
    }

    /** 本人演出列表，可按状态筛选（DRAFT / PUBLISHED / WITHDRAWN，未知值 400） */
    @GetMapping
    public Result<PageResult<PerformanceVO>> list(
            Authentication authentication,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return Result.success(
                performanceService.queryOwned(currentUserId(authentication), status, page, pageSize));
    }

    /** 本人演出详情：草稿、已发布、已下架都可见；他人的 404 */
    @GetMapping("/{id}")
    public Result<PerformanceVO> detail(Authentication authentication, @PathVariable("id") Long id) {
        return Result.success(performanceService.detailOwned(currentUserId(authentication), id));
    }

    /**
     * 新建草稿。请求体可以是空对象——草稿允许信息不完整，
     * 但信息不完整的草稿无法发布（发布时返回 409 PERFORMANCE_NOT_READY）。
     */
    @PostMapping
    public Result<PerformanceVO> create(Authentication authentication,
                                                   @RequestBody SavePerformanceDTO request) {
        return Result.success(performanceService.createDraft(currentUserId(authentication), request));
    }

    /**
     * 编辑演出，PUT 完整对象替换。
     * 已发布/已下架时只有介绍与封面可以改，关键字段被改动则整笔 409，不产生部分更新。
     */
    @PutMapping("/{id}")
    public Result<PerformanceVO> update(Authentication authentication,
                                                   @PathVariable("id") Long id,
                                                   @RequestBody SavePerformanceDTO request) {
        return Result.success(performanceService.update(currentUserId(authentication), id, request));
    }

    /** 发布：仅草稿可发布；重复发布返回当前状态，不重置发布时间与库存 */
    @PostMapping("/{id}/publish")
    public Result<PerformanceVO> publish(Authentication authentication, @PathVariable("id") Long id) {
        return Result.success(performanceService.publish(currentUserId(authentication), id));
    }

    /** 下架：仅已发布可下架；重复下架幂等；草稿下架 409 */
    @PostMapping("/{id}/withdraw")
    public Result<PerformanceVO> withdraw(Authentication authentication, @PathVariable("id") Long id) {
        return Result.success(performanceService.withdraw(currentUserId(authentication), id));
    }

    /**
     * 从认证上下文取当前运营人员编号。
     *
     * <p>/api/operator/** 已被 SecurityConfig 要求 OPERATOR 角色，
     * 因此主体必然是 AppUserPrincipal；这里再兜一层，
     * 万一主体类型不符就返回 401 而不是让类型转换异常变成 500。
     */
    private Long currentUserId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof AppUserPrincipal) {
            return ((AppUserPrincipal) principal).getId();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录或会话已失效");
    }
}
