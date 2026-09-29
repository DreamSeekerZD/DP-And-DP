package com.zddp.ticket.performance.convert;

import com.zddp.ticket.performance.enums.PerformanceStatus;
import com.zddp.ticket.performance.model.entity.Performance;
import com.zddp.ticket.performance.model.vo.PerformanceVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 演出实体到响应的机械字段转换。
 *
 * <p>只做「同名字段搬运 + 时间与时区换算 + 枚举转名字」，
 * 不做任何业务判断：canPurchase、unavailableReason、serverTime 这些需要结合
 * 数据库当前时间与库存才能算出来的字段，一律由 PerformanceService 显式赋值，
 * 因此在这里被声明为 ignore，避免 MapStruct 悄悄填一个错的值。
 *
 * <p>时间换算方向是固定且显式的：数据库里的 DATETIME(3) 语义是 UTC，
 * 这里补上 UTC 偏移对外输出；反过来在写入时由 PerformanceService 显式折算成 UTC。
 * 全程不读取 JVM 默认时区，所以服务器时区怎么设都不会影响结果。
 */
@Mapper
public interface PerformanceConvert {

    @Mapping(target = "startsAt", source = "startsAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "publishedAt", source = "publishedAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "utcLocalToOffset")
    @Mapping(target = "status", source = "status", qualifiedByName = "statusName")
    @Mapping(target = "serverTime", ignore = true)
    @Mapping(target = "canPurchase", ignore = true)
    @Mapping(target = "unavailableReason", ignore = true)
    PerformanceVO toResponse(Performance performance);

    /**
     * 数据库里的 UTC 时间 -> 带 UTC 偏移的对外时间。
     * 空值保持为空：可空时间明确输出 null，不编造默认时间。
     */
    @Named("utcLocalToOffset")
    default OffsetDateTime utcLocalToOffset(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC);
    }

    /** 状态枚举 -> 对外名字（DRAFT / PUBLISHED / WITHDRAWN），不暴露数据库数值 */
    @Named("statusName")
    default String statusName(PerformanceStatus status) {
        return status == null ? null : status.name();
    }
}
