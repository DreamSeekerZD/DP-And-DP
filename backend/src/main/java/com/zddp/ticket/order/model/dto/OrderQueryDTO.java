package com.zddp.ticket.order.model.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 本人订单列表的查询参数，对应 GET /api/orders 的 query string。
 *
 * <p>这只是查询条件，不是业务对象，因此不再单独建查询层或转换层。
 * 用户身份不在这里——它来自服务端会话，不接受查询参数指定，列表永远只看得到本人的订单。
 */
@Getter
@Setter
public class OrderQueryDTO {

    /** 可选：只看某场演出的订单；该演出没有本人订单时返回空列表，不要求演出仍公开 */
    private Long performanceId;

    /** 可选：按状态筛选，只接受 PENDING_PAYMENT / PAID / CLOSED，未知值 400 */
    private String status;

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数，1—100 */
    private int pageSize = 20;
}
