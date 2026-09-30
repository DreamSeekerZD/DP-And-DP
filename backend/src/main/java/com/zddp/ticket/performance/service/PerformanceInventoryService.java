package com.zddp.ticket.performance.service;

import com.zddp.ticket.performance.mapper.PerformanceMapper;
import com.zddp.ticket.performance.model.dto.PerformancePurchaseDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演出模块对外提供的最小交易视图：读取购买信息、占用一张票、归还一张票。
 *
 * <p>为什么单独建这个服务，而不是继续往 {@code PerformanceService} 里加方法：
 * PerformanceService 已经在管草稿/编辑/发布/下架的状态规则，再堆交易逻辑会变成杂糅的大服务。
 * 这里只放交易需要的三件事，职责单一。
 *
 * <p>为什么不给它建接口 + Impl：只有一种实现，没有第二种实现的可能，
 * 拆接口只会多一层无意义的间接。
 *
 * <p><b>事务约定</b>：{@link #reserveOne(Long)} 与 {@link #releaseOne(Long)} 一律以
 * {@code MANDATORY} 加入调用方已有的交易事务——它们绝不自己开事务，也绝不单独提交。
 * 这样「插订单」与「扣库存」（以及「关订单」与「还库存」）才是同一笔原子操作：
 * 任何一步失败，整笔一起回滚，不会留下占了票却没订单、或订单关了票没还的中间状态。
 * 如果调用方没有事务，这两个方法会直接抛异常，属于编程错误，会立刻在测试里暴露。
 */
@Service
public class PerformanceInventoryService {

    private final PerformanceMapper performanceMapper;

    public PerformanceInventoryService(PerformanceMapper performanceMapper) {
        this.performanceMapper = performanceMapper;
    }

    /**
     * 读取下单所需的演出信息。
     *
     * <p>返回 null 表示演出不存在。这里拿到的状态与库存只是「当时看到的」，
     * 不能作为下单的最终依据——最终依据是 reserveOne 的条件更新是否影响到行。
     */
    public PerformancePurchaseDTO getPurchaseInfo(Long performanceId) {
        return performanceMapper.selectPurchaseInfo(performanceId);
    }

    /**
     * 占用一张可售库存。
     *
     * <p>返回 true 表示确实扣掉了一张；false 表示条件不满足（已下架、售罄或已开始），
     * 调用方需要重新读一次演出信息来区分原因，并整笔回滚。
     *
     * <p>必须在调用方事务中执行（MANDATORY）：扣库存与插订单必须同生共死。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveOne(Long performanceId) {
        return performanceMapper.reserveOne(performanceId) == 1;
    }

    /**
     * 归还一张可售库存。
     *
     * <p>返回 true 表示确实还了一张；false 表示上界条件不满足
     * （可售库存已经等于总库存），这说明数据已经不一致，
     * 调用方必须整笔回滚并报告，不能把「没还成功」当成「还过了」。
     *
     * <p>必须在调用方事务中执行（MANDATORY）：关订单与还库存必须同生共死。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseOne(Long performanceId) {
        return performanceMapper.releaseOne(performanceId) == 1;
    }
}
