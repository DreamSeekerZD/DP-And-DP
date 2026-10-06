package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.common.PageResult;
import com.zddp.ticket.order.convert.OrderConvert;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.dto.OrderQueryDTO;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OperatorOrderVO;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.performance.service.PerformanceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本人订单查询：列表与详情，并负责订单 VO 的统一组装。
 *
 * <p>三个要点：
 * <ol>
 *   <li><b>只读</b>：查询不会顺手关闭到期订单。到期但尚未被处理时，
 *       订单仍是 PENDING_PAYMENT，只是 canPay=false，前端应提示「已到支付截止时间，等待关闭」，
 *       不能自己伪造成 CLOSED。真正关闭由用户取消或定时扫描完成。</li>
 *   <li><b>只看本人</b>：用户编号来自服务端会话，他人的订单按不可见返回 404，
 *       不区分「不存在」和「不是你的」，避免探测。</li>
 *   <li><b>用快照</b>：订单自带成交标题、地点、开始时间与金额，
 *       不依赖演出当前是否公开——演出下架后本人订单照常可查。</li>
 * </ol>
 *
 * <p>{@link #toVO} 被下单与关闭链路复用，保证「可支付 / 可取消」这两个判断
 * 在所有出口只有一处实现，不会出现不同接口给出不同答案。
 */
@Service
public class OrderQueryService {

    /** 每页条数上限，与演出列表保持一致 */
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderMapper orderMapper;

    private final OrderConvert orderConvert;

    /**
     * 运营订单查询需要先确认「这场演出归这个运营所有」。
     * 复用 performance 模块的 detailOwned 做归属校验（非本人或不存在 404），
     * 遵守「order 模块可以调用 performance，performance 不反向调用 order」的依赖方向。
     */
    private final PerformanceService performanceService;

    public OrderQueryService(OrderMapper orderMapper,
                             OrderConvert orderConvert,
                             PerformanceService performanceService) {
        this.orderMapper = orderMapper;
        this.orderConvert = orderConvert;
        this.performanceService = performanceService;
    }

    /** 本人订单分页，按 createdAt DESC, id DESC；可按演出与状态筛选 */
    public PageResult<OrderVO> page(Long userId, OrderQueryDTO query) {
        validatePaging(query.getPage(), query.getPageSize());
        Integer statusCode = parseStatusFilter(query.getStatus());

        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        // 与演出列表一致：先转 long 再相乘，极大页码不会溢出成负数偏移
        long offset = ((long) query.getPage() - 1) * query.getPageSize();

        long total = orderMapper.countOwned(userId, query.getPerformanceId(), statusCode);
        List<TicketOrder> rows = orderMapper.selectOwnedPage(
                userId, query.getPerformanceId(), statusCode, offset, query.getPageSize());

        List<OrderVO> items = new ArrayList<OrderVO>(rows.size());
        for (TicketOrder row : rows) {
            items.add(toVO(row, now));
        }
        return new PageResult<OrderVO>(items, total, query.getPage(), query.getPageSize());
    }

    /** 本人订单详情；他人的、以及不存在的编号都返回 404 */
    public OrderVO detail(Long userId, Long orderId) {
        requirePositiveId(orderId);
        TicketOrder order = orderMapper.selectOwnedDetail(orderId, userId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在或不属于当前用户");
        }
        return toVO(order, orderMapper.selectDatabaseNowUtc());
    }

    /**
     * 所属运营查询某场演出的订单，只读。
     *
     * <p>先校验演出归属（非本人 404），再按演出 + 可选状态分页。
     * 输出刻意是裁剪过的 {@link OperatorOrderVO}：不含票号、用户身份、可操作提示。
     * 分页期间并发变更不保证总数固定，这是分页接口的一般语义，不是错误。
     */
    public PageResult<OperatorOrderVO> pageForOperator(Long publisherId, Long performanceId, OrderQueryDTO query) {
        requirePositiveId(performanceId);
        validatePaging(query.getPage(), query.getPageSize());
        Integer statusCode = parseStatusFilter(query.getStatus());

        // 归属校验：演出不存在或不属于当前运营都返回 404
        performanceService.detailOwned(publisherId, performanceId);

        long offset = ((long) query.getPage() - 1) * query.getPageSize();
        long total = orderMapper.countOperator(performanceId, statusCode);
        List<TicketOrder> rows = orderMapper.selectOperatorPage(performanceId, statusCode, offset, query.getPageSize());

        List<OperatorOrderVO> items = new ArrayList<OperatorOrderVO>(rows.size());
        for (TicketOrder row : rows) {
            items.add(orderConvert.toOperatorVO(row));
        }
        return new PageResult<OperatorOrderVO>(items, total, query.getPage(), query.getPageSize());
    }

    /**
     * 组装订单 VO 并计算当前可操作性。
     *
     * <p>serverTime 由调用方传入，保证同一次响应里的所有判断基于同一时刻。
     * canPay / canCancel 只是「这一瞬间的提示」，写接口仍然要自己重新校验，
     * 不能拿前端看到的值当依据。
     */
    public OrderVO toVO(TicketOrder order, LocalDateTime serverTimeUtc) {
        OrderVO vo = orderConvert.toVO(order);
        vo.setServerTime(serverTimeUtc.atOffset(ZoneOffset.UTC));

        boolean pending = order.getStatus() == OrderStatus.PENDING_PAYMENT;
        // 待支付且尚未到截止时间才可支付；时间相等即视为已到期
        vo.setCanPay(pending && serverTimeUtc.isBefore(order.getExpireAt()));
        // 待支付即可取消：已经到期也允许点，由后端按数据库时间记为 PAY_TIMEOUT
        vo.setCanCancel(pending);
        return vo;
    }

    private void validatePaging(int page, int pageSize) {
        if (page < 1) {
            throw BusinessException.validation("page 必须大于等于 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.validation("pageSize 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.validation("订单编号必须是正整数");
        }
    }

    /** 状态筛选只接受三个约定值；未知值 400，不静默忽略 */
    private Integer parseStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.trim().isEmpty()) {
            return null;
        }
        String normalized = statusFilter.trim().toUpperCase(Locale.ROOT);
        for (OrderStatus status : OrderStatus.values()) {
            if (status.name().equals(normalized)) {
                return status.getCode();
            }
        }
        throw BusinessException.validation("未知的订单状态筛选值：" + statusFilter);
    }
}
