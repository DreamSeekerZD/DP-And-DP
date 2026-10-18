package com.zddp.ticket.order.redis;

/**
 * 精准释放（{@link OrderReservationStore#release}）的结果。
 *
 * <p>由 `redis/order-release.lua` 的整数返回码映射而来（两 key 收敛版，无 owner Hash/身份参数）。
 * 仅当该用户仍在 occupied 集合中（ZREM 实际移除 1 个成员）才归还一份库存 → RELEASED；
 * 成员不存在（从未占用或已释放）→ NOOP；缺库存/非法值/错误 key 类型/加 1 越界抛
 * {@link OrderReservationException}，不得无条件归还。
 *
 * <p>注意：本结果不区分“哪一次占用”，不保证“重购后的旧释放”安全（见
 * {@link OrderReservationStore} 类注释），B 段必须约束调用路径。
 */
public enum OrderReleaseResult {

    /** 释放成功：该用户在 occupied 集合中，已移除成员并归还一份库存 */
    RELEASED,

    /** 空操作：该用户不在 occupied 集合中（从未占用、已释放或重复释放） */
    NOOP
}