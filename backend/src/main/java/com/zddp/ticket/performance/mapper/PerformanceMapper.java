package com.zddp.ticket.performance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zddp.ticket.performance.model.dto.PerformancePurchaseDTO;
import com.zddp.ticket.performance.model.entity.Performance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演出表访问。
 *
 * <p>本模块的 SQL 全部放在 {@code resources/mapper/performance/PerformanceMapper.xml}：
 * 这里包含行锁、限定原状态的条件更新和显式列清单更新，都属于必须一眼看清的关键 SQL，
 * 不适合用 MyBatis-Plus 的通用 updateById 代替。
 *
 * <p>刻意不提供「按对象整体更新」的方法：状态与库存只能通过下面这些带条件的语句变更，
 * 避免前端对象直接覆盖掉状态、发布时间或已售库存。
 */
@Mapper
public interface PerformanceMapper extends BaseMapper<Performance> {

    /**
     * 数据库当前时间（UTC，毫秒精度）。
     * 「开始时间是否已过」「发布时间写多少」一律以此为准，
     * 不用应用机器时间，避免多机时钟不一致与 JVM 时区干扰。
     */
    LocalDateTime selectDatabaseNowUtc();

    /**
     * 按 id + 所属运营人员锁定一行（SELECT ... FOR UPDATE）。
     * 编辑、发布、下架都在短事务内先取这把行锁，拿到锁之后再判断状态与时间，
     * 保证「编辑与发布并发」不会绕过字段锁定规则。
     * 返回 null 表示演出不存在或不属于该运营人员，调用方按 404 处理。
     */
    Performance selectOwnedForUpdate(@Param("id") Long id, @Param("publisherId") Long publisherId);

    /** 新建草稿，回填自增主键 */
    int insertPerformance(Performance performance);

    /**
     * 草稿整体替换可编辑字段。带 status = 0 条件，
     * 若这一行已被并发发布，则影响行数为 0，调用方必须据此回滚而不是当作成功。
     */
    int updateDraft(Performance performance);

    /**
     * 已发布/已下架只允许修改介绍与封面。
     * 列清单是写死的：即使上层传入了别的字段，也不会被写进数据库。
     */
    int updateDisplayFields(@Param("id") Long id,
                            @Param("publisherId") Long publisherId,
                            @Param("description") String description,
                            @Param("coverUrl") String coverUrl,
                            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 草稿转已发布。带 status = 0 条件，因此发布时间只会被写一次；
     * 重复发布不会命中这一行，也就不会重置发布时间或库存。
     */
    int publishDraft(@Param("id") Long id,
                     @Param("publisherId") Long publisherId,
                     @Param("publishedAt") LocalDateTime publishedAt);

    /**
     * 已发布转已下架。带 status = 1 条件，重复下架影响行数为 0（幂等），
     * 草稿执行下架同样不会命中。
     */
    int withdrawPublished(@Param("id") Long id,
                          @Param("publisherId") Long publisherId,
                          @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 公开列表：只含已发布；已开始、已售罄仍然展示，只是不可购买。
     * offset 用 long：页码是 int，但 (page - 1) * pageSize 可能超出 int 范围
     * （例如 page=2147483647、pageSize=100），用 int 承载会溢出成负数偏移。
     */
    List<Performance> selectPublicPage(@Param("offset") long offset, @Param("limit") int limit);

    /** 公开列表总数 */
    long countPublic();

    /** 公开详情：草稿与已下架一律查不到，由调用方转成 404 */
    Performance selectPublicDetail(@Param("id") Long id);

    /** 运营列表：只看得到本人内容，可选按状态筛选。offset 用 long 的理由同 selectPublicPage。 */
    List<Performance> selectOwnedPage(@Param("publisherId") Long publisherId,
                                      @Param("status") Integer status,
                                      @Param("offset") long offset,
                                      @Param("limit") int limit);

    /** 运营列表总数 */
    long countOwned(@Param("publisherId") Long publisherId, @Param("status") Integer status);

    /** 运营详情：本人所有状态都可见，他人的查不到 */
    Performance selectOwnedDetail(@Param("id") Long id, @Param("publisherId") Long publisherId);

    // ===================== 下单链路的库存操作（B02） =====================

    /**
     * 下单所需的演出购买信息。刻意只取交易判断要用的列，不含介绍与封面。
     *
     * <p>这里**不加锁**：它只用于给出可售状态与快照，
     * 真正的并发保护是下面 {@link #reserveOne(Long)} 的条件更新。
     * 返回 null 表示演出不存在，调用方按 404 处理。
     */
    PerformancePurchaseDTO selectPurchaseInfo(@Param("id") Long id);

    /**
     * 条件扣减一张可售库存，成功返回 1，否则返回 0。
     *
     * <p>四个条件缺一不可：编号、已发布、可售库存大于 0、尚未开始。
     * 后两个条件由数据库在**拿到行锁之后**重新求值，所以：
     * <ul>
     *   <li>并发争抢最后一张票时只有一个事务能扣成功，另一个影响行数为 0 并整笔回滚，不会超卖；</li>
     *   <li>如果这条 UPDATE 因为别人持锁而等待，等它拿到锁时「尚未开始」会用当时的时间重新判断，
     *       开演后不会再把票卖出去。</li>
     * </ul>
     * 影响行数为 0 时调用方需要重新读一次演出，区分「售罄」「已下架」「已开始」，不能一律当成售罄。
     */
    int reserveOne(@Param("id") Long id);

    /**
     * 恢复一张可售库存，成功返回 1，否则返回 0。
     *
     * <p>上界限制 available_stock &lt; total_stock：重复释放不会把库存加超，
     * 影响行数为 0 说明数据已经不一致，调用方必须整笔回滚并报告，不能当作成功。
     *
     * <p>刻意**不限制**演出是否仍然发布、是否已经开始：演出下架或开演之后关闭订单，
     * 该释放的库存仍要释放。
     */
    int releaseOne(@Param("id") Long id);
}
