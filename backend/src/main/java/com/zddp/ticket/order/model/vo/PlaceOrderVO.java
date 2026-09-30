package com.zddp.ticket.order.model.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 下单结果，对应 POST /api/orders 的 data。
 *
 * <p>{@code created} 是给前端的幂等提示：
 * <ul>
 *   <li>true：本次真的新建了订单，占用了一张票；</li>
 *   <li>false：本人对这场演出已经有有效订单，直接返回原单，没有再次扣减库存。</li>
 * </ul>
 * 两种情况都是 HTTP 200，前端不应把 created=false 当成系统错误。
 */
@Getter
@Setter
public class PlaceOrderVO {

    /** 本次是否新建了订单 */
    private boolean created;

    /** 订单内容（新建的或复用的原单） */
    private OrderVO order;

    public PlaceOrderVO() {
    }

    public PlaceOrderVO(boolean created, OrderVO order) {
        this.created = created;
        this.order = order;
    }
}
