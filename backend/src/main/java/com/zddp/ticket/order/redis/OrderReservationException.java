package com.zddp.ticket.order.redis;

/**
 * Redis 预占数据异常。
 *
 * <p>抛出该异常只表示 Redis 中本次演出的占用数据已损坏、或库存缺失到无法安全归还，
 * 必须人工维护确认后才恢复该演出的新购买准入。调用方不得在本请求内悄悄“修复”、
 * 不得无条件补库存，也不得把该结果伪装成正常业务结果。
 */
public class OrderReservationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrderReservationException(String message) {
        super(message);
    }
}