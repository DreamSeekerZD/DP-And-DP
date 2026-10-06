package com.zddp.ticket.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zddp.ticket.order.enums.OrderCloseReason;
import com.zddp.ticket.order.model.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单表访问。
 *
 * <p>SQL 全部放在 {@code resources/mapper/order/OrderMapper.xml}：这里包含行锁、
 * 限定原状态的条件更新和显式列清单插入，都属于必须一眼看清的关键 SQL。
 *
 * <p>刻意不提供「按对象整体更新」的方法：状态只能通过带原状态条件的语句变更，
 * 避免把订单对象直接写回去时顺手改掉状态、时间或票号。
 *
 * <p>插入语句的列清单里**没有 active_marker**：那是数据库生成列，
 * 由 status 推导，应用写入会绕过「一人一演出一张有效单」的唯一约束。
 */
@Mapper
public interface OrderMapper extends BaseMapper<TicketOrder> {

    /**
     * 数据库当前时间（UTC，毫秒精度）。
     * 「是否到期」「关闭原因」「截止时间」一律以此为准，不用应用机器时间。
     */
    LocalDateTime selectDatabaseNowUtc();

    /**
     * 按 id + 下单用户锁定订单（SELECT ... FOR UPDATE）。
     * 取消订单在短事务里先取这把行锁，拿到锁之后再判断状态与是否到期。
     * 返回 null 表示订单不存在或不属于该用户，调用方按 404 处理。
     */
    TicketOrder selectOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按订单号锁定订单，不带用户条件。
     * 只给后台超时关闭使用：那个入口不接受浏览器身份，扫描出来的订单号本身就是它的依据。
     * 用户入口一律用 {@link #selectOwnedForUpdate}，避免越权关闭他人订单。
     */
    TicketOrder selectForUpdateById(@Param("id") Long id);

    /**
     * 本人对某演出的**有效订单**（待支付或已支付）。
     * 唯一索引 uk_order_user_performance_active 保证至多返回一条；没有则返回 null。
     * 这是「重复下单返回原单」的依据，优先于演出是否售罄或下架的判断。
     */
    TicketOrder selectActiveByUserAndPerformance(@Param("userId") Long userId,
                                                 @Param("performanceId") Long performanceId);

    /** 按 id + 下单用户查询订单（不加锁），用于本人详情 */
    TicketOrder selectOwnedDetail(@Param("id") Long id, @Param("userId") Long userId);

    /** 插入订单并回填自增主键；不写 active_marker */
    int insertOrder(TicketOrder order);

    /**
     * 待支付 -> 已支付：一次写入支付时间与票号。
     * 带 status = 0 条件，因此只可能成功一次；影响行数必须是 1，
     * 否则说明状态已被并发改写，服务层整笔回滚，不能假装支付成功。
     * 本语句不改动 active_marker——那是生成列，跟随 status 自动变化。
     */
    int payPending(@Param("id") Long id,
                   @Param("paidAt") LocalDateTime paidAt,
                   @Param("ticketNo") String ticketNo);

    /**
     * 待支付 -> 已关闭。带 status = 0 条件，因此只可能成功一次；
     * 影响行数为 0 表示这单已被并发关闭或已支付，调用方必须据此回滚或跳过，
     * 不能当成关闭成功去恢复库存。
     */
    int closePending(@Param("id") Long id,
                     @Param("closeReason") OrderCloseReason closeReason,
                     @Param("closedAt") LocalDateTime closedAt);

    /**
     * 超时扫描候选：已到支付截止时间但仍待支付的订单号。
     * 按 (expire_at, id) 稳定排序，LIMIT 上限不使用 OFFSET，
     * 下一轮从头再扫，避免翻页期间漏单。
     */
    List<Long> selectTimeoutCandidates(@Param("limit") int limit);

    /**
     * 本人订单分页，按 createdAt DESC, id DESC。
     * offset 用 long：(page - 1) * pageSize 可能超出 int 范围。
     */
    List<TicketOrder> selectOwnedPage(@Param("userId") Long userId,
                                      @Param("performanceId") Long performanceId,
                                      @Param("status") Integer status,
                                      @Param("offset") long offset,
                                      @Param("limit") int limit);

    /** 本人订单总数，筛选条件与 selectOwnedPage 一致 */
    long countOwned(@Param("userId") Long userId,
                    @Param("performanceId") Long performanceId,
                    @Param("status") Integer status);

    /**
     * 所属运营查询某场演出的订单分页。
     * 只按 performance_id 过滤：归属校验由服务层先做（演出不存在或非本人 404），
     * 这里不允许客户端用任何方式决定查询的范围。
     * offset 用 long，理由与 selectOwnedPage 相同。
     */
    List<TicketOrder> selectOperatorPage(@Param("performanceId") Long performanceId,
                                         @Param("status") Integer status,
                                         @Param("offset") long offset,
                                         @Param("limit") int limit);

    /** 同演出的订单总数，筛选条件与 selectOperatorPage 一致 */
    long countOperator(@Param("performanceId") Long performanceId, @Param("status") Integer status);
}
