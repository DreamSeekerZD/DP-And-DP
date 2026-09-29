package com.zddp.ticket.performance.controller;

import com.zddp.ticket.common.Result;
import com.zddp.ticket.common.PageResult;
import com.zddp.ticket.performance.model.vo.PerformanceVO;
import com.zddp.ticket.performance.service.PerformanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开演出接口，匿名可访问。
 *
 * <p>只暴露已发布内容：草稿与已下架一律按不存在处理（404），
 * 运营查看自己的内容走 /api/operator/performances。
 */
@RestController
@RequestMapping("/api/performances")
public class PerformanceController {

    private final PerformanceService performanceService;

    public PerformanceController(PerformanceService performanceService) {
        this.performanceService = performanceService;
    }

    /**
     * 公开演出列表，按 createdAt DESC, id DESC 排序。
     *
     * <p>已开始、已售罄的演出仍然出现在列表里（只是不可购买），
     * 可售性由每项的 canPurchase 与 unavailableReason 表达。
     */
    @GetMapping
    public Result<PageResult<PerformanceVO>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return Result.success(performanceService.queryPublic(page, pageSize));
    }

    /**
     * 公开演出详情。
     * 路径参数必须是正整数，非法值（例如 /api/performances/abc 或 /0）返回 400；
     * 草稿、已下架、以及不存在的编号都返回 404。
     */
    @GetMapping("/{id}")
    public Result<PerformanceVO> detail(@PathVariable("id") Long id) {
        return Result.success(performanceService.detailPublic(id));
    }
}
