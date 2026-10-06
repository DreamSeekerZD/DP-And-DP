package com.zddp.ticket.order.controller;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.common.PageResult;
import com.zddp.ticket.common.Result;
import com.zddp.ticket.order.model.dto.OrderQueryDTO;
import com.zddp.ticket.order.model.vo.OperatorOrderVO;
import com.zddp.ticket.order.service.OrderQueryService;
import com.zddp.ticket.user.model.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营订单查询接口：所属运营查看自己某场演出的订单。
 *
 * <p>路径在 /api/operator/** 之下，由 SecurityConfig 统一要求 OPERATOR 角色
 * （普通用户访问得到 403）。路由本身带演出编号，运营身份来自服务端会话。
 *
 * <p>输出刻意不含票号与用户身份（见 OperatorOrderVO），只读、无导出无汇总。
 */
@RestController
@RequestMapping("/api/operator/performances/{performanceId}/orders")
public class OperatorOrderController {

    private final OrderQueryService orderQueryService;

    public OperatorOrderController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    /**
     * 本人演出的订单分页，可按状态筛选。
     * 演出不存在或不属于当前运营：404；未知状态：400。
     */
    @GetMapping
    public Result<PageResult<OperatorOrderVO>> list(
            Authentication authentication,
            @PathVariable("performanceId") Long performanceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        OrderQueryDTO query = new OrderQueryDTO();
        query.setStatus(status);
        query.setPage(page);
        query.setPageSize(pageSize);
        return Result.success(
                orderQueryService.pageForOperator(currentPublisherId(authentication), performanceId, query));
    }

    /**
     * 从认证上下文取当前运营人员编号。
     * /api/operator/** 已要求 OPERATOR 角色，主体必然是 AppUserPrincipal；
     * 这里再兜一层，类型不符时返回 401 而不是让类型转换异常变成 500。
     */
    private Long currentPublisherId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof AppUserPrincipal) {
            return ((AppUserPrincipal) principal).getId();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录或会话已失效");
    }
}