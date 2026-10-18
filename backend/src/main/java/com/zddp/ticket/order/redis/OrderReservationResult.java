package com.zddp.ticket.order.redis;

/**
 * 预占请求（{@link OrderReservationStore#reserve}）的结果（两 key 收敛版，无 owner Hash）。
 *
 * <p>由 `redis/order-reserve.lua` 的整数返回码映射而来，本组件不查 MySQL。
 * 结果语义遵循 B05 原子操作合同：
 *
 * <ul>
 *   <li>先校验 key 类型、库存字符串严格格式与时间值；</li>
 *   <li>再判用户是否已占用（OCCUPIED 优先于 SOLD_OUT，保证买过的用户可找回订单）；</li>
 *   <li>最后判库存；无库存时不写任何用户记录。</li>
 * </ul>
 */
public enum OrderReservationResult {

    /** 本次预占成功：Redis 库存已减一，并在 occupied ZSet 登记该用户 */
    RESERVED,

    /** 该用户已有占用：应回查订单而不是重复建单；score 不被刷新 */
    OCCUPIED,

    /** Redis 可售库存已为 0；数据库条件扣库存的最终一致性由 B05-B 的订单事务再保证 */
    SOLD_OUT,

    /** 数据未就绪或已损坏：库存 key 缺失/非法值/错误类型、时间值非法。
     *  必须人工维护确认，本组件不在此请求内悄悄初始化或部分写入。 */
    NOT_READY
}