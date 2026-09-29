package com.zddp.ticket.performance.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 演出状态。数据库保存 0/1/2，固定映射，不使用 {@code ordinal()}，
 * 避免将来枚举顺序调整破坏已有数据。
 *
 * <p>状态转换只有两条，都要求从明确的原状态出发：
 * <ul>
 *   <li>DRAFT -> PUBLISHED：首次发布，写入发布时间；</li>
 *   <li>PUBLISHED -> WITHDRAWN：下架，停止新增购买，不回退草稿，也不允许重新发布。</li>
 * </ul>
 *
 * <p>「已开始」「售罄」不是状态，而是由开始时间与库存实时算出来的展示结果。
 */
public enum PerformanceStatus {

    /** 草稿：可编辑，公开接口不可见 */
    DRAFT(0),

    /** 已发布：公开可见，可购买（受开始时间与库存影响） */
    PUBLISHED(1),

    /** 已下架：停止新增购买，不回到草稿，禁止重新发布 */
    WITHDRAWN(2);

    /** 数据库存储值 */
    @EnumValue
    private final int code;

    PerformanceStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按数据库数值还原状态。
     * 遇到未知数值说明数据被外部改坏了，这里直接抛错而不是猜一个默认值，
     * 避免把异常数据当成合法演出继续对外服务。
     */
    public static PerformanceStatus fromCode(int code) {
        for (PerformanceStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的演出状态数值: " + code);
    }
}
