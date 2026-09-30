package com.zddp.ticket.order.convert;

import com.zddp.ticket.order.enums.OrderCloseReason;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 订单实体到订单 VO 的机械字段转换。
 *
 * <p>只做「同名字段搬运 + 时间换算 + 枚举转名字」，不做任何业务判断：
 * serverTime、canPay、canCancel 需要结合数据库当前时间与订单状态才能算出来，
 * 一律由 OrderQueryService 显式赋值，所以在这里被声明为 ignore，
 * 避免 MapStruct 悄悄填一个错的默认值。
 *
 * <p>时间换算与 B01 一致：数据库列 DATETIME(3) 的语义是 UTC，
 * 这里补上 UTC 偏移对外输出，全程不读取 JVM 默认时区。
 */
@Mapper
public interface OrderConvert {

    @Mapping(target = "performanceStartsAt", source = "performanceStartsAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "expireAt", source = "expireAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "paidAt", source = "paidAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "closedAt", source = "closedAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "status", source = "status", qualifiedByName = "statusName")
    @Mapping(target = "closeReason", source = "closeReason", qualifiedByName = "closeReasonName")
    @Mapping(target = "serverTime", ignore = true)
    @Mapping(target = "canPay", ignore = true)
    @Mapping(target = "canCancel", ignore = true)
    OrderVO toVO(TicketOrder order);

    /** 数据库里的 UTC 时间 -> 带 UTC 偏移的对外时间；空值保持为空 */
    @Named("utcLocalToOffset")
    default OffsetDateTime utcLocalToOffset(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC);
    }

    /** 状态枚举 -> 对外名字（PENDING_PAYMENT / PAID / CLOSED），不暴露数据库数值 */
    @Named("statusName")
    default String statusName(OrderStatus status) {
        return status == null ? null : status.name();
    }

    /** 关闭原因枚举 -> 对外名字；未关闭时为空 */
    @Named("closeReasonName")
    default String closeReasonName(OrderCloseReason reason) {
        return reason == null ? null : reason.name();
    }
}
