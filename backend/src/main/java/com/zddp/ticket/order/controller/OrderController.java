package com.zddp.ticket.order.controller;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.common.PageResult;
import com.zddp.ticket.common.Result;
import com.zddp.ticket.order.model.dto.CancelOrderDTO;
import com.zddp.ticket.order.model.dto.CreateOrderDTO;
import com.zddp.ticket.order.model.dto.OrderQueryDTO;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.order.model.vo.PlaceOrderVO;
import com.zddp.ticket.order.service.OrderCloseService;
import com.zddp.ticket.order.service.OrderPlaceService;
import com.zddp.ticket.order.service.OrderQueryService;
import com.zddp.ticket.user.model.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人订单接口，要求 USER 角色（由 SecurityConfig 统一拦截：匿名 401，运营账号 403）。
 *
 * <p>「这单是谁的」完全来自服务端会话中的认证上下文。
 * 请求体或查询参数里出现的 userId、amountCent、quantity、status 之类字段不会被采纳：
 * 它们要么属于未知字段直接 400，要么只能由服务端根据演出与订单自身状态推导。
 *
 * <p>查询接口是只读的，不会顺手关闭到期订单。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderPlaceService orderPlaceService;

    private final OrderCloseService orderCloseService;

    private final OrderQueryService orderQueryService;

    public OrderController(OrderPlaceService orderPlaceService,
                           OrderCloseService orderCloseService,
                           OrderQueryService orderQueryService) {
        this.orderPlaceService = orderPlaceService;
        this.orderCloseService = orderCloseService;
        this.orderQueryService = orderQueryService;
    }

    /**
     * 下单，每单固定一张。
     *
     * <p>返回 200：{@code created=true} 表示这次真的新建并占了一张票；
     * {@code created=false} 表示本人对这场演出已有有效订单，直接返回原单
     * （已支付或未到期的待支付都走这里，重复提交不是错误）。
     */
    @PostMapping
    public Result<PlaceOrderVO> create(Authentication authentication,
                                       @RequestBody CreateOrderDTO request) {
        return Result.success(
                orderPlaceService.place(currentUserId(authentication), request.getPerformanceId()));
    }

    /** 本人订单列表，可按演出与状态筛选；只看得到自己的订单 */
    @GetMapping
    public Result<PageResult<OrderVO>> list(
            Authentication authentication,
            @RequestParam(name = "performanceId", required = false) Long performanceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        OrderQueryDTO query = new OrderQueryDTO();
        query.setPerformanceId(performanceId);
        query.setStatus(status);
        query.setPage(page);
        query.setPageSize(pageSize);
        return Result.success(orderQueryService.page(currentUserId(authentication), query));
    }

    /** 本人订单详情：成交快照、状态、时间与当前可操作性；他人的 404 */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(Authentication authentication, @PathVariable("id") Long id) {
        return Result.success(orderQueryService.detail(currentUserId(authentication), id));
    }

    /**
     * 取消待支付订单。
     *
     * <p>不接受任何参数：目标状态、关闭原因与库存释放都由服务端决定。
     * 请求体可以完全没有，也可以是 {@code {}}；带字段会被未知字段规则挡成 400。
     * 重复取消返回原结果，不会重复释放库存。
     */
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(Authentication authentication,
                                  @PathVariable("id") Long id,
                                  @RequestBody(required = false) CancelOrderDTO request) {
        return Result.success(orderCloseService.cancel(currentUserId(authentication), id));
    }

    /**
     * 从认证上下文取当前用户编号。
     * /api/orders/** 已被要求 USER 角色，主体必然是 AppUserPrincipal；
     * 这里再兜一层，类型不符时返回 401 而不是让类型转换异常变成 500。
     */
    private Long currentUserId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof AppUserPrincipal) {
            return ((AppUserPrincipal) principal).getId();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录或会话已失效");
    }
}
